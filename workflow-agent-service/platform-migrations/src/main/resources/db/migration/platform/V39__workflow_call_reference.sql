-- Which published process version calls which process key, through which call activity.
-- Written when a version is published. Drives cycle detection and "where used".
CREATE TABLE workflow_call_reference (
    tenant_id              VARCHAR(64)  NOT NULL,
    parent_definition_id   VARCHAR(128) NOT NULL,
    parent_definition_key  VARCHAR(128) NOT NULL,
    parent_version         INTEGER      NOT NULL,
    activity_id            VARCHAR(255) NOT NULL,
    called_key             VARCHAR(128) NOT NULL,
    CONSTRAINT pk_workflow_call_reference PRIMARY KEY (tenant_id, parent_definition_id, activity_id)
);

CREATE INDEX ix_workflow_call_reference_called ON workflow_call_reference (tenant_id, called_key);
CREATE INDEX ix_workflow_call_reference_parent_key ON workflow_call_reference (tenant_id, parent_definition_key);

ALTER TABLE workflow_call_reference ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_call_reference FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_workflow_call_reference ON workflow_call_reference
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));
