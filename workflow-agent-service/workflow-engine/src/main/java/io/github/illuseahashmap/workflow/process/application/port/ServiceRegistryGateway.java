package io.github.illuseahashmap.workflow.process.application.port;

import java.util.Map;
import java.util.Optional;

public interface ServiceRegistryGateway {

    Optional<PublishedCallContract> findPublished(String serviceKey, String operationKey);

    Map<String, Object> invoke(String serviceKey, String operationKey, Map<String, Object> resolvedInputs);
}
