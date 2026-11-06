package io.github.illuseahashmap.workflow.process.infrastructure.persistence;

import io.github.illuseahashmap.workflow.process.application.port.CallReferenceRepository;
import io.github.illuseahashmap.workflow.process.domain.CallReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCallReferenceRepository implements CallReferenceRepository {

    private static final String COLUMNS =
            "parent_definition_id, parent_definition_key, parent_version, activity_id, called_key";

    private final JdbcTemplate jdbc;

    public JdbcCallReferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void replaceForDefinition(String tenantId, String parentDefinitionId, List<CallReference> references) {
        deleteForDefinition(tenantId, parentDefinitionId);
        for (CallReference reference : references) {
            jdbc.update("INSERT INTO workflow_call_reference (tenant_id, " + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)",
                    tenantId, reference.parentDefinitionId(), reference.parentDefinitionKey(),
                    reference.parentVersion(), reference.activityId(), reference.calledKey());
        }
    }

    @Override
    public void deleteForDefinition(String tenantId, String parentDefinitionId) {
        jdbc.update("DELETE FROM workflow_call_reference WHERE tenant_id = ? AND parent_definition_id = ?",
                tenantId, parentDefinitionId);
    }

    @Override
    public void deleteForKey(String tenantId, String parentDefinitionKey) {
        jdbc.update("DELETE FROM workflow_call_reference WHERE tenant_id = ? AND parent_definition_key = ?",
                tenantId, parentDefinitionKey);
    }

    @Override
    public List<CallReference> findCallers(String tenantId, String calledKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow_call_reference WHERE tenant_id = ? AND called_key = ?"
                        + " ORDER BY parent_definition_key, parent_version DESC, activity_id",
                (rs, row) -> map(rs), tenantId, calledKey);
    }

    @Override
    public List<CallReference> findByDefinition(String tenantId, String parentDefinitionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow_call_reference"
                        + " WHERE tenant_id = ? AND parent_definition_id = ? ORDER BY activity_id",
                (rs, row) -> map(rs), tenantId, parentDefinitionId);
    }

    @Override
    public List<CallReference> findAll(String tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow_call_reference WHERE tenant_id = ?",
                (rs, row) -> map(rs), tenantId);
    }

    private static CallReference map(ResultSet rs) throws SQLException {
        return new CallReference(rs.getString("parent_definition_id"), rs.getString("parent_definition_key"),
                rs.getInt("parent_version"), rs.getString("activity_id"), rs.getString("called_key"));
    }
}
