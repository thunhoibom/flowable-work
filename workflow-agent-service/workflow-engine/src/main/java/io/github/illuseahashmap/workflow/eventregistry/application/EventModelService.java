package io.github.illuseahashmap.workflow.eventregistry.application;

import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventModelView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveEventModelRequest;
import java.util.List;

/** The tenant's library of event models (spec 2026-11-09-event-registry-d1-design, slice 3). */
public interface EventModelService {

    List<EventModelView> list();

    EventModelView get(String key);

    EventModelView saveDraft(String key, SaveEventModelRequest request);

    /** Deploys the draft to Flowable when its content changed; refuses incompatible changes. */
    EventModelView publish(String key, long revision);

    /** Refused while any wait, process or channel still uses the event. */
    void delete(String key);
}
