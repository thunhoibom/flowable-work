package io.github.illuseahashmap.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits for stored process documents, drafts and published versions alike. */
@ConfigurationProperties("workflow.definition")
public class ProcessDefinitionProperties {

    /** Upper bound for a BPMN document, in UTF-8 bytes. */
    private int maxBpmnBytes = 2_097_152;

    public int getMaxBpmnBytes() {
        return maxBpmnBytes;
    }

    public void setMaxBpmnBytes(int maxBpmnBytes) {
        this.maxBpmnBytes = maxBpmnBytes;
    }
}
