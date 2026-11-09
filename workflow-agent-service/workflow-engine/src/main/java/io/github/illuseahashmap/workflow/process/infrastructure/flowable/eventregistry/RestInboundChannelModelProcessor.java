package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.eventregistry.api.ChannelModelProcessor;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.flowable.eventregistry.model.ChannelModel;

/**
 * Validates and remembers deployed REST channels, per tenant and key. Opens no listener.
 * Flowable hands every processor the same model instance; the core InboundChannelModelProcessor
 * attaches the JSON pipeline to it, so the instance kept here is ready for eventReceived.
 */
public class RestInboundChannelModelProcessor implements ChannelModelProcessor {

    private static final String NO_TENANT = "";
    private final Map<String, RestInboundChannelModel> channels = new ConcurrentHashMap<>();

    @Override
    public boolean canProcess(ChannelModel channelModel) {
        return channelModel instanceof RestInboundChannelModel;
    }

    @Override
    public boolean canProcessIfChannelModelAlreadyRegistered(ChannelModel channelModel) {
        return canProcess(channelModel);
    }

    @Override
    public void registerChannelModel(ChannelModel channelModel, String tenantId, EventRegistry eventRegistry,
            EventRepositoryService eventRepositoryService, boolean fallbackToDefaultTenant) {
        RestInboundChannelModel model = (RestInboundChannelModel) channelModel;
        if (model.getSecurity().isEmpty()) {
            throw new FlowableIllegalArgumentException(
                    "REST channel " + model.getKey() + " must allow at least one user or group");
        }
        if (!"json".equals(model.getDeserializerType())) {
            throw new FlowableIllegalArgumentException(
                    "REST channel " + model.getKey() + " must use deserializerType json");
        }
        channels.put(id(tenantId, model.getKey()), model);
    }

    @Override
    public void unregisterChannelModel(ChannelModel channelModel, String tenantId,
            EventRepositoryService eventRepositoryService) {
        // Only drop the exact instance being unregistered: a newer version may already be in place.
        channels.remove(id(tenantId, channelModel.getKey()), channelModel);
    }

    /** The registered channel, once its pipeline is attached. */
    public Optional<RestInboundChannelModel> find(String tenantId, String channelKey) {
        return Optional.ofNullable(channels.get(id(tenantId, channelKey)))
                .filter(model -> model.getInboundEventProcessingPipeline() != null);
    }

    private static String id(String tenantId, String key) {
        return (tenantId == null ? NO_TENANT : tenantId) + "\u0000" + key;
    }
}
