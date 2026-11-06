package io.github.illuseahashmap.workflow.process.infrastructure.persistence;

import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProcessReleaseRepository implements ProcessReleaseRepository {

    private static final String COLUMNS = "id, tenant_id, process_definition_key, process_definition_id, version, "
            + "action, source_version, xml_sha256, performed_by, performed_at";

    private final JdbcTemplate jdbc;

    public JdbcProcessReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(ProcessRelease release) {
        jdbc.update("""
                INSERT INTO workflow_process_release (tenant_id, process_definition_key, process_definition_id,
                    version, action, source_version, xml_sha256, performed_by, performed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, release.tenantId(), release.processDefinitionKey(), release.processDefinitionId(),
                release.version(), release.action().name(), release.sourceVersion(), release.xmlSha256(),
                release.performedBy(), release.performedAt());
    }

    @Override
    public List<ProcessRelease> list(String tenantId, String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow_process_release "
                        + "WHERE tenant_id = ? AND process_definition_key = ? ORDER BY performed_at DESC, id DESC",
                (rs, row) -> map(rs), tenantId, key);
    }

    @Override
    public boolean hasAny(String tenantId, String key) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM workflow_process_release WHERE tenant_id = ? AND process_definition_key = ?",
                Integer.class, tenantId, key);
        return count != null && count > 0;
    }

    @Override
    public Map<String, ProcessRelease> latestByDefinition(String tenantId) {
        return jdbc.query("SELECT DISTINCT ON (process_definition_id) " + COLUMNS
                        + " FROM workflow_process_release WHERE tenant_id = ?"
                        + " ORDER BY process_definition_id, performed_at DESC, id DESC",
                        (rs, row) -> map(rs), tenantId).stream()
                .collect(Collectors.toMap(ProcessRelease::processDefinitionId, Function.identity()));
    }

    @Override
    public void deleteByKey(String tenantId, String key) {
        jdbc.update("DELETE FROM workflow_process_release WHERE tenant_id = ? AND process_definition_key = ?",
                tenantId, key);
    }

    private static ProcessRelease map(ResultSet rs) throws SQLException {
        return new ProcessRelease(rs.getLong("id"), rs.getString("tenant_id"),
                rs.getString("process_definition_key"), rs.getString("process_definition_id"),
                rs.getInt("version"), ReleaseAction.valueOf(rs.getString("action")),
                rs.getObject("source_version", Integer.class), rs.getString("xml_sha256"),
                rs.getString("performed_by"), rs.getObject("performed_at", OffsetDateTime.class));
    }
}
