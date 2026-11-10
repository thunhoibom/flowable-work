package io.github.illuseahashmap.workflow.eventregistry.application;

import io.github.illuseahashmap.workflow.eventregistry.application.dto.RestChannelView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveRestChannelRequest;
import java.util.List;

/** The tenant's inbound REST channels (spec 2026-11-09-event-registry-d1-design, slice 4). */
public interface RestChannelService {

    List<RestChannelView> list();

    RestChannelView get(String key);

    RestChannelView saveDraft(String key, SaveRestChannelRequest request);

    /** Deploys the channel when its content changed. Every allowed event must already be published. */
    RestChannelView publish(String key, long revision);

    void delete(String key);
}
