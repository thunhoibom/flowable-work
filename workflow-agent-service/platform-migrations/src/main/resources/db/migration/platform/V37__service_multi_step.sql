-- Pagination and webhook wait. Old rows stay one synchronous call.
ALTER TABLE service_operation_draft
    ADD COLUMN step_json TEXT NOT NULL DEFAULT '{}';

-- One waiting service task. The row is removed when the callback arrives or the wait expires.
CREATE TABLE service_callback_wait (
    correlation_id VARCHAR(256) PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL,
    service_key VARCHAR(128) NOT NULL,
    operation_key VARCHAR(128) NOT NULL,
    output_mapping_json TEXT NOT NULL,
    deadline_epoch_ms BIGINT NOT NULL
);
