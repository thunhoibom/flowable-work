CREATE TABLE service_definition (
    service_key VARCHAR(128) PRIMARY KEY,
    name VARCHAR(256) NOT NULL
);

CREATE TABLE service_operation_draft (
    service_key VARCHAR(128) NOT NULL REFERENCES service_definition(service_key),
    operation_key VARCHAR(128) NOT NULL,
    method VARCHAR(8) NOT NULL,
    url_template TEXT NOT NULL,
    auth_type VARCHAR(16) NOT NULL,
    api_key_header VARCHAR(128),
    secret_cipher TEXT,
    body_template TEXT,
    timeout_millis INTEGER NOT NULL,
    error_status_codes VARCHAR(256) NOT NULL,
    bpmn_error_code VARCHAR(128) NOT NULL,
    PRIMARY KEY (service_key, operation_key)
);

CREATE TABLE service_publication (
    service_key VARCHAR(128) PRIMARY KEY REFERENCES service_definition(service_key),
    snapshot_json TEXT NOT NULL,
    published_at TIMESTAMP NOT NULL
);
