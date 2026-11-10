package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryGateway;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryModelType;
import java.util.List;
import java.util.Optional;
import org.flowable.engine.RuntimeService;
import org.flowable.eventregistry.api.ChannelDefinition;
import org.flowable.eventregistry.api.EventDefinition;
import org.flowable.eventregistry.api.EventDeployment;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.springframework.stereotype.Component;

@Component
public class FlowableEventRegistryGateway implements EventRegistryGateway {

    private final EventRepositoryService eventRepositoryService;
    private final RuntimeService runtimeService;

    public FlowableEventRegistryGateway(EventRepositoryService eventRepositoryService, RuntimeService runtimeService) {
        this.eventRepositoryService = eventRepositoryService;
        this.runtimeService = runtimeService;
    }

    @Override
    public Deployed deployEvent(String tenantId, String key, String json) {
        EventDeployment deployment = eventRepositoryService.createDeployment()
                .name(RegistryModelType.EVENT.deploymentName(key)).tenantId(tenantId)
                .addEventDefinition(key + ".event", json).deploy();
        EventDefinition definition = eventRepositoryService.createEventDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        return new Deployed(definition.getId(), deployment.getId(), definition.getVersion());
    }

    @Override
    public Deployed deployChannel(String tenantId, String key, String json) {
        EventDeployment deployment = eventRepositoryService.createDeployment()
                .name(RegistryModelType.CHANNEL.deploymentName(key)).tenantId(tenantId)
                .addChannelDefinition(key + ".channel", json).deploy();
        ChannelDefinition definition = eventRepositoryService.createChannelDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        return new Deployed(definition.getId(), deployment.getId(), definition.getVersion());
    }

    @Override
    public Optional<List<EventField>> publishedEventFields(String tenantId, String key) {
        boolean exists = eventRepositoryService.createEventDefinitionQuery()
                .eventDefinitionKey(key).tenantId(tenantId).count() > 0;
        return exists
                ? Optional.of(FlowableEventRegistryJson.fields(eventRepositoryService.getEventModelByKey(key, tenantId)))
                : Optional.empty();
    }

    @Override
    public boolean channelDeployed(String tenantId, String key) {
        return eventRepositoryService.createChannelDefinitionQuery()
                .channelDefinitionKey(key).tenantId(tenantId).count() > 0;
    }

    @Override
    public void deleteDeployments(String tenantId, RegistryModelType type, String key) {
        eventRepositoryService.createDeploymentQuery()
                .deploymentName(type.deploymentName(key)).deploymentTenantId(tenantId).list()
                .forEach(deployment -> eventRepositoryService.deleteDeployment(deployment.getId()));
    }

    @Override
    public long waitingSubscriptions(String tenantId, String eventKey) {
        return runtimeService.createEventSubscriptionQuery().eventType(eventKey).tenantId(tenantId).count();
    }
}
