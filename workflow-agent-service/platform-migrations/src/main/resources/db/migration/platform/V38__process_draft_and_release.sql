-- Draft / publish separation: drafts live here, only publishing deploys to Flowable.
CREATE TABLE workflow_process_draft (
    tenant_id              VARCHAR(64)  NOT NULL,
    process_definition_key VARCHAR(128) NOT NULL,
    name                   VARCHAR(255) NOT NULL,
    bpmn_xml               TEXT         NOT NULL,
    xml_sha256             CHAR(64)     NOT NULL,
    revision               BIGINT       NOT NULL,
    updated_by             VARCHAR(255) NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_workflow_process_draft PRIMARY KEY (tenant_id, process_definition_key)
);

CREATE TABLE workflow_process_release (
    id                     BIGSERIAL    PRIMARY KEY,
    tenant_id              VARCHAR(64)  NOT NULL,
    process_definition_key VARCHAR(128) NOT NULL,
    process_definition_id  VARCHAR(128) NOT NULL,
    version                INTEGER      NOT NULL,
    action                 VARCHAR(16)  NOT NULL,
    source_version         INTEGER,
    xml_sha256             CHAR(64)     NOT NULL,
    performed_by           VARCHAR(255) NOT NULL,
    performed_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_workflow_process_release_action
        CHECK (action IN ('PUBLISH', 'ROLLBACK', 'SUSPEND', 'RESUME'))
);

CREATE INDEX ix_workflow_process_release_key
    ON workflow_process_release (tenant_id, process_definition_key, performed_at DESC);
CREATE INDEX ix_workflow_process_release_definition
    ON workflow_process_release (tenant_id, process_definition_id, performed_at DESC);

ALTER TABLE workflow_process_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_process_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_workflow_process_draft ON workflow_process_draft
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE workflow_process_release ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_process_release FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_workflow_process_release ON workflow_process_release
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));
