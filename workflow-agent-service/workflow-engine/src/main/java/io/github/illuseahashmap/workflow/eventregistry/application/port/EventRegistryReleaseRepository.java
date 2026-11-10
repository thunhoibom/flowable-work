package io.github.illuseahashmap.workflow.eventregistry.application.port;

import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryModelType;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryRelease;
import java.util.Optional;

public interface EventRegistryReleaseRepository {

    void record(RegistryRelease release);

    Optional<RegistryRelease> latest(String tenantId, RegistryModelType type, String key);
}
