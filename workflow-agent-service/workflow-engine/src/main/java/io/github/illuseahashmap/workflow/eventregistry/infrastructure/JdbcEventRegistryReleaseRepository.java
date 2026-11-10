package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryReleaseRepository;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryModelType;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryRelease;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEventRegistryReleaseRepository implements EventRegistryReleaseRepository {

    private final JdbcTemplate jdbc;

    public JdbcEventRegistryReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(RegistryRelease release) {
        jdbc.update("""
                INSERT INTO workflow_event_registry_release (tenant_id, model_type, model_key, flowable_version,
                    flowable_definition_id, flowable_deployment_id, deployed_json_sha256, action,
                    performed_by, performed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, release.tenantId(), release.type().name(), release.key(), release.flowableVersion(),
                release.flowableDefinitionId(), release.flowableDeploymentId(), release.deployedJsonSha256(),
                release.action(), release.performedBy(), release.performedAt());
    }

    @Override
    public Optional<RegistryRelease> latest(String tenantId, RegistryModelType type, String key) {
        return jdbc.query("""
                SELECT tenant_id, model_type, model_key, flowable_version, flowable_definition_id,
                       flowable_deployment_id, deployed_json_sha256, action, performed_by, performed_at
                FROM workflow_event_registry_release
                WHERE tenant_id = ? AND model_type = ? AND model_key = ?
                ORDER BY performed_at DESC, id DESC LIMIT 1
                """, (rs, row) -> new RegistryRelease(rs.getString("tenant_id"),
                        RegistryModelType.valueOf(rs.getString("model_type")), rs.getString("model_key"),
                        rs.getObject("flowable_version", Integer.class), rs.getString("flowable_definition_id"),
                        rs.getString("flowable_deployment_id"), rs.getString("deployed_json_sha256"),
                        rs.getString("action"), rs.getString("performed_by"),
                        rs.getObject("performed_at", OffsetDateTime.class)),
                tenantId, type.name(), key).stream().findFirst();
    }
}
