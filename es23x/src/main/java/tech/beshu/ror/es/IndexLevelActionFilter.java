/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package tech.beshu.ror.es;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.env.Environment;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import tech.beshu.ror.acl.ACL;
import tech.beshu.ror.commons.settings.BasicSettings;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.shims.es.ACLHandler;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;


/**
 * Created by sscarduzio on 19/12/2015.
 */
@Singleton
public class IndexLevelActionFilter extends AbstractComponent implements ActionFilter {

  private final ThreadPool threadPool;
  private final ClusterService clusterService;

  private final AtomicReference<Optional<ACL>> acl;
  private final AtomicReference<ESContext> context = new AtomicReference<>();
  private final LoggerShim loggerShim;
  private final IndexNameExpressionResolver indexResolver;

  private NodeClient client;

  @Inject
  public IndexLevelActionFilter(Settings settings,
                                ClusterService clusterService,
                                TransportService transportService,
                                ThreadPool threadPool,
                                SettingsObservableImpl settingsObservable,
                                IndexNameExpressionResolver indexResolver
  )
    throws IOException {
    super(settings);

    loggerShim = ESContextImpl.mkLoggerShim(logger);

    Environment env = new Environment(settings);
    BasicSettings baseSettings = BasicSettings.fromFile(loggerShim, env.configFile().toAbsolutePath(), settings.getAsStructuredMap());

    this.context.set(new ESContextImpl(client, baseSettings));

    this.clusterService = clusterService;
    this.indexResolver = indexResolver;
    this.threadPool = threadPool;
    this.acl = new AtomicReference<>(Optional.empty());

    new TaskManagerWrapper(settings).injectIntoTransportService(transportService, loggerShim);


    ReadonlyRestPlugin.clientFuture.thenAccept(c -> {

      // Have to do this because guice goes crazy otherwise..
      settingsObservable.setClient(c);

      settingsObservable.addObserver((o, arg) -> {
        logger.info("Settings observer refreshing...");
        Environment newEnv = new Environment(settings);
        RawSettings raw = new RawSettings(settingsObservable.getCurrent().asMap());
        BasicSettings newBasicSettings = new BasicSettings(raw, newEnv.configFile().toAbsolutePath());
        ESContext newContext = new ESContextImpl(client, newBasicSettings);

        if (newContext.getSettings().isEnabled()) {
          try {
            ACL newAcl = new ACL(newContext);
            acl.set(Optional.of(newAcl));
            logger.info("Configuration reloaded - ReadonlyREST enabled");
          } catch (Exception ex) {
            logger.error("Cannot configure ReadonlyREST plugin", ex);
            throw ex;
          }
        }
        else {
          acl.set(Optional.empty());
          logger.info("Configuration reloaded - ReadonlyREST disabled");
        }

        try {
          this.client = c;
          settingsObservable.pollForIndex(context.get());
          logger.info("Readonly REST plugin was loaded...");
        } catch (Throwable e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
      });


    });

    settingsObservable.forceRefresh();
    logger.info("Readonly REST plugin was loaded...");

  }


  @Override
  public int order() {
    return 0;
  }


  @Override
  public void apply(String action, ActionResponse response, ActionListener listener, ActionFilterChain chain) {
    chain.proceed(action, response, listener);
  }

  @Override
  public void apply(Task task,
                    String action,
                    ActionRequest request,
                    ActionListener listener,
                    ActionFilterChain chain) {


    Optional<ACL> acl = this.acl.get();
    if (acl.isPresent()) {
      handleRequest(acl.get(), task, action, request, listener, chain);
    }
    else {
      chain.proceed(task, action, request, listener);
    }
  }

  private <Request extends ActionRequest, Response extends ActionResponse> void handleRequest(ACL acl,
                                                                                              Task task,
                                                                                              String action,
                                                                                              Request request,
                                                                                              ActionListener<Response> listener,
                                                                                              ActionFilterChain chain) {
    RestChannel channel = ThreadRepo.channel.get();
    if (channel != null) {
      ThreadRepo.channel.remove();
    }
    boolean chanNull = channel == null;
    boolean reqNull = channel == null ? true : channel.request() == null;
    if (ACL.shouldSkipACL(chanNull, reqNull)) {
      chain.proceed(task, action, request, listener);
      return;
    }
    RequestInfo requestInfo = new RequestInfo(channel, action, request, clusterService, threadPool, context.get(), indexResolver);
    acl.check(requestInfo, new ACLHandler() {
      @Override
      public void onForbidden() {
        sendNotAuthResponse(channel, context.get().getSettings());
      }

      @Override
      public void onAllow(Object blockExitResult) {
        boolean hasProceeded = false;
        try {
          //         @SuppressWarnings("unchecked")
//          ActionListener<Response> aclActionListener = (ActionListener<Response>) new ACLActionListener(
//            request, (ActionListener<ActionResponse>) listener, rc, blockExitResult, context, acl
//          );
//          chain.proceed(task, action, request, aclActionListener);

          chain.proceed(task, action, request, listener);
          hasProceeded = true;
          return;
        } catch (Throwable e) {
          e.printStackTrace();
        }
        if (!hasProceeded) {
          chain.proceed(task, action, request, listener);
        }
      }

      @Override
      public boolean isNotFound(Throwable throwable) {
        return throwable.getCause() instanceof ResourceNotFoundException;
      }

      @Override
      public void onNotFound(Throwable throwable) {
        sendNotFound((ResourceNotFoundException) throwable.getCause(), channel);
      }

      @Override
      public void onErrored(Throwable t) {
        sendNotAuthResponse(channel, context.get().getSettings());
      }
    });

  }

  private void sendNotAuthResponse(RestChannel channel, BasicSettings basicSettings) {
    BytesRestResponse resp;
    boolean doesRequirePassword = acl.get().map(ACL::doesRequirePassword).orElse(false);
    if (doesRequirePassword) {
      resp = new BytesRestResponse(RestStatus.UNAUTHORIZED, BytesRestResponse.TEXT_CONTENT_TYPE, basicSettings.getForbiddenMessage());
      logger.debug("Sending login prompt header...");
      resp.addHeader("WWW-Authenticate", "Basic");
    }
    else {
      resp = new BytesRestResponse(RestStatus.FORBIDDEN, BytesRestResponse.TEXT_CONTENT_TYPE, basicSettings.getForbiddenMessage());
    }

    channel.sendResponse(resp);
  }

  private void sendNotFound(ResourceNotFoundException e, RestChannel channel) {
    try {
      XContentBuilder b = JsonXContent.contentBuilder();
      b.startObject();
      ElasticsearchException.renderThrowable(b, ToXContent.EMPTY_PARAMS, e);
      b.endObject();
      BytesRestResponse resp;
      resp = new BytesRestResponse(RestStatus.NOT_FOUND, "application/json", b.string());
      channel.sendResponse(resp);
    } catch (Exception e1) {
      e1.printStackTrace();
    }
  }

}
