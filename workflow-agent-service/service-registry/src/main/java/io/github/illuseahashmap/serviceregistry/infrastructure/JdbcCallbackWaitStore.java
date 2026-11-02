package io.github.illuseahashmap.serviceregistry.infrastructure;

import io.github.illuseahashmap.serviceregistry.domain.CallbackWait;
import io.github.illuseahashmap.serviceregistry.domain.CallbackWaitStore;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Postgres wait rows. DELETE RETURNING makes a callback and a timeout unable to both win. */
@Repository
public class JdbcCallbackWaitStore implements CallbackWaitStore {

    private static final RowMapper<CallbackWait> WAIT = (resultSet, row) -> new CallbackWait(
            resultSet.getString("correlation_id"),
            resultSet.getString("execution_id"),
            resultSet.getString("service_key"),
            resultSet.getString("operation_key"),
            resultSet.getString("output_mapping_json"),
            resultSet.getLong("deadline_epoch_ms"));

    private final JdbcTemplate jdbc;

    public JdbcCallbackWaitStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(CallbackWait wait) {
        try {
            jdbc.update("""
                    INSERT INTO service_callback_wait (
                        correlation_id, execution_id, service_key, operation_key,
                        output_mapping_json, deadline_epoch_ms)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    wait.correlationId(),
                    wait.executionId(),
                    wait.serviceKey(),
                    wait.operationKey(),
                    wait.outputMappingJson(),
                    wait.deadlineEpochMs());
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalStateException("Correlation id is already waiting");
        }
    }

    @Override
    public Optional<CallbackWait> take(String correlationId) {
        List<CallbackWait> removed = jdbc.query("""
                DELETE FROM service_callback_wait
                WHERE correlation_id = ?
                RETURNING correlation_id, execution_id, service_key, operation_key,
                          output_mapping_json, deadline_epoch_ms
                """, WAIT, correlationId);
        return removed.stream().findFirst();
    }

    @Override
    public List<CallbackWait> takeExpired(long nowEpochMs) {
        return jdbc.query("""
                DELETE FROM service_callback_wait
                WHERE deadline_epoch_ms <= ?
                RETURNING correlation_id, execution_id, service_key, operation_key,
                          output_mapping_json, deadline_epoch_ms
                """, WAIT, nowEpochMs);
    }
}
