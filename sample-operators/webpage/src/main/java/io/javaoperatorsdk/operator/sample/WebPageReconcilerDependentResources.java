package io.javaoperatorsdk.operator.sample;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusHandler;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependentResourceConfig;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.PrimaryToSecondaryMapper;

import static io.javaoperatorsdk.operator.ReconcilerUtils.loadYaml;

/**
 * Shows how to implement reconciler using standalone dependent resources.
 */
@ControllerConfiguration(
    labelSelector = WebPageReconcilerDependentResources.DEPENDENT_RESOURCE_LABEL_SELECTOR)
public class WebPageReconcilerDependentResources
    implements Reconciler<WebPage>, ErrorStatusHandler<WebPage>, EventSourceInitializer<WebPage> {

  public static final String DEPENDENT_RESOURCE_LABEL_SELECTOR = "!low-level";
  private static final Logger log =
      LoggerFactory.getLogger(WebPageReconcilerDependentResources.class);
  private final KubernetesClient kubernetesClient;

  private KubernetesDependentResource<ConfigMap, WebPage> configMapDR;
  private KubernetesDependentResource<Deployment, WebPage> deploymentDR;
  private KubernetesDependentResource<Service, WebPage> serviceDR;

  public WebPageReconcilerDependentResources(KubernetesClient kubernetesClient) {
    this.kubernetesClient = kubernetesClient;
    createDependentResources(kubernetesClient);
  }

  @Override
  public List<EventSource> prepareEventSources(EventSourceContext<WebPage> context) {
    return List.of(
        configMapDR.initEventSource(context),
        deploymentDR.initEventSource(context),
        serviceDR.initEventSource(context));
  }

  @Override
  public UpdateControl<WebPage> reconcile(WebPage webPage, Context<WebPage> context) {
    if (webPage.getSpec().getHtml().contains("error")) {
      // special case just to showcase error if doing a demo
      throw new ErrorSimulationException("Simulating error");
    }

    configMapDR.reconcile(webPage, context);
    deploymentDR.reconcile(webPage, context);
    serviceDR.reconcile(webPage, context);

    webPage.setStatus(
        createStatus(configMapDR.getResource(webPage).orElseThrow().getMetadata().getName()));
    return UpdateControl.updateStatus(webPage);
  }

  private WebPageStatus createStatus(String configMapName) {
    WebPageStatus status = new WebPageStatus();
    status.setHtmlConfigMap(configMapName);
    status.setAreWeGood(true);
    status.setErrorMessage(null);
    return status;
  }

  @Override
  public ErrorStatusUpdateControl<WebPage> updateErrorStatus(
      WebPage resource, Context<WebPage> retryInfo, Exception e) {
    resource.getStatus().setErrorMessage("Error: " + e.getMessage());
    return ErrorStatusUpdateControl.updateStatus(resource);
  }

  private void createDependentResources(KubernetesClient client) {
    this.configMapDR = new ConfigMapDependentResource();
    this.configMapDR.setKubernetesClient(client);
    configMapDR.configureWith(new KubernetesDependentResourceConfig()
        .setLabelSelector(DEPENDENT_RESOURCE_LABEL_SELECTOR));

    this.deploymentDR =
        new CRUDKubernetesDependentResource<>(Deployment.class) {

          @Override
          protected Deployment desired(WebPage webPage, Context<WebPage> context) {
            var deploymentName = deploymentName(webPage);
            Deployment deployment = loadYaml(Deployment.class, getClass(), "deployment.yaml");
            deployment.getMetadata().setName(deploymentName);
            deployment.getMetadata().setNamespace(webPage.getMetadata().getNamespace());
            deployment.getSpec().getSelector().getMatchLabels().put("app", deploymentName);

            deployment
                .getSpec()
                .getTemplate()
                .getMetadata()
                .getLabels()
                .put("app", deploymentName);
            deployment
                .getSpec()
                .getTemplate()
                .getSpec()
                .getVolumes()
                .get(0)
                .setConfigMap(
                    new ConfigMapVolumeSourceBuilder().withName(configMapName(webPage)).build());
            return deployment;
          }
        };
    deploymentDR.setKubernetesClient(client);
    deploymentDR.configureWith(new KubernetesDependentResourceConfig()
        .setLabelSelector(DEPENDENT_RESOURCE_LABEL_SELECTOR));

    this.serviceDR =
        new CRUDKubernetesDependentResource<>(Service.class) {

          @Override
          protected Service desired(WebPage webPage, Context<WebPage> context) {
            Service service = loadYaml(Service.class, getClass(), "service.yaml");
            service.getMetadata().setName(serviceName(webPage));
            service.getMetadata().setNamespace(webPage.getMetadata().getNamespace());
            Map<String, String> labels = new HashMap<>();
            labels.put("app", deploymentName(webPage));
            service.getSpec().setSelector(labels);
            return service;
          }
        };
    serviceDR.setKubernetesClient(client);
    serviceDR.configureWith(new KubernetesDependentResourceConfig()
        .setLabelSelector(DEPENDENT_RESOURCE_LABEL_SELECTOR));
  }

  public static String configMapName(WebPage nginx) {
    return nginx.getMetadata().getName() + "-html";
  }

  public static String deploymentName(WebPage nginx) {
    return nginx.getMetadata().getName();
  }

  public static String serviceName(WebPage webPage) {
    return webPage.getMetadata().getName();
  }

  private class ConfigMapDependentResource
      extends CRUKubernetesDependentResource<ConfigMap, WebPage>
      implements PrimaryToSecondaryMapper<WebPage> {

    public ConfigMapDependentResource() {
      super(ConfigMap.class);
    }

    @Override
    protected ConfigMap desired(WebPage webPage, Context<WebPage> context) {
      Map<String, String> data = new HashMap<>();
      data.put("index.html", webPage.getSpec().getHtml());
      return new ConfigMapBuilder()
          .withMetadata(
              new ObjectMetaBuilder()
                  .withName(WebPageReconcilerDependentResources.configMapName(webPage))
                  .withNamespace(webPage.getMetadata().getNamespace())
                  .build())
          .withData(data)
          .build();
    }

    @Override
    public ConfigMap update(ConfigMap actual, ConfigMap target, WebPage primary,
        Context<WebPage> context) {
      var res = super.update(actual, target, primary, context);
      var ns = actual.getMetadata().getNamespace();
      log.info("Restarting pods because HTML has changed in {}", ns);
      kubernetesClient
          .pods()
          .inNamespace(ns)
          .withLabel("app", deploymentName(primary))
          .delete();
      return res;
    }

    @Override
    public ResourceID associatedSecondaryID(WebPage primary) {
      return new ResourceID(configMapName(primary), primary.getMetadata().getNamespace());
    }
  }
}