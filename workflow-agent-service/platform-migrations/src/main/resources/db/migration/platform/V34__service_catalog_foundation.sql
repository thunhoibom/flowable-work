ALTER TABLE service_definition
    ADD COLUMN description VARCHAR(2000) NOT NULL DEFAULT '',
    ADD COLUMN base_urls_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN shared_auth_json TEXT NOT NULL DEFAULT '{"type":"NONE","apiKeyHeader":null}';

ALTER TABLE service_operation_draft
    ADD COLUMN auth_mode VARCHAR(16) NOT NULL DEFAULT 'OVERRIDE',
    ADD COLUMN connect_timeout_millis INTEGER NOT NULL DEFAULT 5000,
    ADD COLUMN error_rules_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN success_statuses VARCHAR(256) NOT NULL DEFAULT '',
    ADD COLUMN call_policy_json TEXT NOT NULL DEFAULT '{"maxAttempts":1,"retryStatuses":[],"backoffMillis":0,"followRedirects":true,"ignoreErrors":false}';

-- Existing operations used one timeout for both connect and read.
UPDATE service_operation_draft
SET connect_timeout_millis = timeout_millis;
