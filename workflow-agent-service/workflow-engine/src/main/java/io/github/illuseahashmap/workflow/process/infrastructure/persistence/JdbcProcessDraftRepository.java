package io.github.illuseahashmap.workflow.process.infrastructure.persistence;

import io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraftHeader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProcessDraftRepository implements ProcessDraftRepository {

    private static final String COLUMNS =
            "tenant_id, process_definition_key, name, bpmn_xml, xml_sha256, revision, updated_by, updated_at";

    private final JdbcTemplate jdbc;

    public JdbcProcessDraftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProcessDraft> find(String tenantId, String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow_process_draft "
                        + "WHERE tenant_id = ? AND process_definition_key = ?",
                (rs, row) -> map(rs), tenantId, key).stream().findFirst();
    }

    @Override
    public Optional<ProcessDraft> findForUpdate(String tenantId, String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow_process_draft "
                        + "WHERE tenant_id = ? AND process_definition_key = ? FOR UPDATE",
                (rs, row) -> map(rs), tenantId, key).stream().findFirst();
    }

    @Override
    public boolean insert(ProcessDraft draft) {
        try {
            jdbc.update("INSERT INTO workflow_process_draft (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    draft.tenantId(), draft.processDefinitionKey(), draft.name(), draft.bpmnXml(),
                    draft.xmlSha256(), draft.revision(), draft.updatedBy(), draft.updatedAt());
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public boolean update(ProcessDraft draft, long expectedRevision) {
        return jdbc.update("""
                UPDATE workflow_process_draft
                SET name = ?, bpmn_xml = ?, xml_sha256 = ?, revision = ?, updated_by = ?, updated_at = ?
                WHERE tenant_id = ? AND process_definition_key = ? AND revision = ?
                """, draft.name(), draft.bpmnXml(), draft.xmlSha256(), draft.revision(),
                draft.updatedBy(), draft.updatedAt(),
                draft.tenantId(), draft.processDefinitionKey(), expectedRevision) == 1;
    }

    @Override
    public List<ProcessDraftHeader> listHeaders(String tenantId) {
        return jdbc.query("""
                SELECT process_definition_key, name, xml_sha256, revision, updated_by, updated_at
                FROM workflow_process_draft WHERE tenant_id = ? ORDER BY process_definition_key
                """, (rs, row) -> new ProcessDraftHeader(
                        rs.getString("process_definition_key"), rs.getString("name"),
                        rs.getString("xml_sha256"), rs.getLong("revision"), rs.getString("updated_by"),
                        rs.getObject("updated_at", OffsetDateTime.class)), tenantId);
    }

    @Override
    public void delete(String tenantId, String key) {
        jdbc.update("DELETE FROM workflow_process_draft WHERE tenant_id = ? AND process_definition_key = ?",
                tenantId, key);
    }

    private static ProcessDraft map(ResultSet rs) throws SQLException {
        return new ProcessDraft(rs.getString("tenant_id"), rs.getString("process_definition_key"),
                rs.getString("name"), rs.getString("bpmn_xml"), rs.getString("xml_sha256"),
                rs.getLong("revision"), rs.getString("updated_by"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
