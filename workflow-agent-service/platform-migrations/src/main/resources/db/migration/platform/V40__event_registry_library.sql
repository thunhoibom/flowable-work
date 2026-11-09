-- Event registry library (spec 2026-11-09-event-registry-d1-design). No inbound event journal:
-- idempotency uses platform_inbox_event.

CREATE TABLE workflow_event_registry_draft (
    tenant_id      VARCHAR(64)   NOT NULL,
    model_type     VARCHAR(16)   NOT NULL,
    model_key      VARCHAR(128)  NOT NULL,
    name           VARCHAR(256)  NOT NULL,
    description    VARCHAR(1024),
    model_json     TEXT          NOT NULL,
    revision       BIGINT        NOT NULL,
    updated_by     VARCHAR(255)  NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_workflow_event_registry_draft PRIMARY KEY (tenant_id, model_type, model_key),
    CONSTRAINT ck_workflow_event_registry_draft_type CHECK (model_type IN ('EVENT', 'CHANNEL'))
);

CREATE TABLE workflow_event_registry_release (
    id                      BIGSERIAL     PRIMARY KEY,
    tenant_id               VARCHAR(64)   NOT NULL,
    model_type              VARCHAR(16)   NOT NULL,
    model_key               VARCHAR(128)  NOT NULL,
    flowable_version        INTEGER,
    flowable_definition_id  VARCHAR(64),
    flowable_deployment_id  VARCHAR(64),
    deployed_json_sha256    CHAR(64),
    action                  VARCHAR(16)   NOT NULL,
    performed_by            VARCHAR(255)  NOT NULL,
    performed_at            TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_workflow_event_registry_release_type CHECK (model_type IN ('EVENT', 'CHANNEL')),
    CONSTRAINT ck_workflow_event_registry_release_action CHECK (action IN ('PUBLISH', 'DELETE'))
);

CREATE INDEX ix_workflow_event_registry_release_key
    ON workflow_event_registry_release (tenant_id, model_type, model_key, performed_at DESC);

CREATE TABLE workflow_event_reference (
    tenant_id              VARCHAR(64)   NOT NULL,
    process_definition_id  VARCHAR(128)  NOT NULL,
    activity_id            VARCHAR(255)  NOT NULL,
    event_key              VARCHAR(128)  NOT NULL,
    CONSTRAINT pk_workflow_event_reference PRIMARY KEY (tenant_id, process_definition_id, activity_id)
);

CREATE INDEX ix_workflow_event_reference_event
    ON workflow_event_reference (tenant_id, event_key);

ALTER TABLE workflow_event_registry_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_event_registry_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_workflow_event_registry_draft ON workflow_event_registry_draft
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE workflow_event_registry_release ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_event_registry_release FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_workflow_event_registry_release ON workflow_event_registry_release
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE workflow_event_reference ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_event_reference FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_workflow_event_reference ON workflow_event_reference
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));
