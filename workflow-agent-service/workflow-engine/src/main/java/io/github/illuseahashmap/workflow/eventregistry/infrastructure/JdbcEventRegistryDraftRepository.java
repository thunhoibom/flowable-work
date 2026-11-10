package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryDraftRepository;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryDraft;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryModelType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEventRegistryDraftRepository implements EventRegistryDraftRepository {

    private static final String COLUMNS =
            "tenant_id, model_type, model_key, name, description, model_json, revision, updated_by, updated_at";

    private final JdbcTemplate jdbc;

    public JdbcEventRegistryDraftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RegistryDraft> find(String tenantId, RegistryModelType type, String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow_event_registry_draft "
                        + "WHERE tenant_id = ? AND model_type = ? AND model_key = ?",
                (rs, row) -> map(rs), tenantId, type.name(), key).stream().findFirst();
    }

    @Override
    public Optional<RegistryDraft> findForUpdate(String tenantId, RegistryModelType type, String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow_event_registry_draft "
                        + "WHERE tenant_id = ? AND model_type = ? AND model_key = ? FOR UPDATE",
                (rs, row) -> map(rs), tenantId, type.name(), key).stream().findFirst();
    }

    @Override
    public boolean insert(RegistryDraft draft) {
        try {
            jdbc.update("INSERT INTO workflow_event_registry_draft (" + COLUMNS + ") "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    draft.tenantId(), draft.type().name(), draft.key(), draft.name(), draft.description(),
                    draft.modelJson(), draft.revision(), draft.updatedBy(), draft.updatedAt());
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public boolean update(RegistryDraft draft, long expectedRevision) {
        return jdbc.update("""
                UPDATE workflow_event_registry_draft
                SET name = ?, description = ?, model_json = ?, revision = ?, updated_by = ?, updated_at = ?
                WHERE tenant_id = ? AND model_type = ? AND model_key = ? AND revision = ?
                """, draft.name(), draft.description(), draft.modelJson(), draft.revision(),
                draft.updatedBy(), draft.updatedAt(),
                draft.tenantId(), draft.type().name(), draft.key(), expectedRevision) == 1;
    }

    @Override
    public List<RegistryDraft> list(String tenantId, RegistryModelType type) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow_event_registry_draft "
                        + "WHERE tenant_id = ? AND model_type = ? ORDER BY model_key",
                (rs, row) -> map(rs), tenantId, type.name());
    }

    @Override
    public void delete(String tenantId, RegistryModelType type, String key) {
        jdbc.update("DELETE FROM workflow_event_registry_draft WHERE tenant_id = ? AND model_type = ? AND model_key = ?",
                tenantId, type.name(), key);
    }

    private static RegistryDraft map(ResultSet rs) throws SQLException {
        return new RegistryDraft(rs.getString("tenant_id"), RegistryModelType.valueOf(rs.getString("model_type")),
                rs.getString("model_key"), rs.getString("name"), rs.getString("description"),
                rs.getString("model_json"), rs.getLong("revision"), rs.getString("updated_by"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
