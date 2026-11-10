package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.eventregistry.application.port.EventReferenceRepository;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventReference;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEventReferenceRepository implements EventReferenceRepository {

    /** References of the latest process definition version of each key. */
    private static final String LATEST = """
            FROM workflow_event_reference reference
            JOIN act_re_procdef definition ON definition.id_ = reference.process_definition_id
            WHERE reference.tenant_id = ? AND reference.event_key = ?
              AND definition.version_ = (SELECT MAX(latest.version_) FROM act_re_procdef latest
                                         WHERE latest.key_ = definition.key_
                                           AND latest.tenant_id_ = definition.tenant_id_)
            """;

    private final JdbcTemplate jdbc;

    public JdbcEventReferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void replaceForDefinition(String tenantId, String processDefinitionId, List<EventReference> references) {
        deleteForDefinition(tenantId, processDefinitionId);
        for (EventReference reference : references) {
            jdbc.update("""
                    INSERT INTO workflow_event_reference (tenant_id, process_definition_id, activity_id, event_key)
                    VALUES (?, ?, ?, ?)
                    """, tenantId, reference.processDefinitionId(), reference.activityId(), reference.eventKey());
        }
    }

    @Override
    public void deleteForDefinition(String tenantId, String processDefinitionId) {
        jdbc.update("DELETE FROM workflow_event_reference WHERE tenant_id = ? AND process_definition_id = ?",
                tenantId, processDefinitionId);
    }

    @Override
    public void deleteForProcessKey(String tenantId, String processDefinitionKey) {
        jdbc.update("""
                DELETE FROM workflow_event_reference r USING act_re_procdef d
                WHERE r.tenant_id = ? AND d.id_ = r.process_definition_id AND d.key_ = ? AND d.tenant_id_ = ?
                """, tenantId, processDefinitionKey, tenantId);
    }

    @Override
    public long latestProcessDefinitionsUsing(String tenantId, String eventKey) {
        Long count = jdbc.queryForObject("SELECT COUNT(DISTINCT reference.process_definition_id) " + LATEST,
                Long.class, tenantId, eventKey);
        return count == null ? 0 : count;
    }

    @Override
    public List<EventUsage> usages(String tenantId, String eventKey) {
        return jdbc.query("SELECT definition.key_, definition.version_, reference.activity_id " + LATEST
                        + " ORDER BY definition.key_, reference.activity_id",
                (rs, row) -> new EventUsage(rs.getString(1), rs.getInt(2), rs.getString(3)), tenantId, eventKey);
    }
}
