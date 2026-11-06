package io.github.illuseahashmap.workflow.process.domain;

import java.time.OffsetDateTime;

/** The editable copy of a process. The engine never sees it until it is published. */
public record ProcessDraft(
        String tenantId,
        String processDefinitionKey,
        String name,
        String bpmnXml,
        String xmlSha256,
        long revision,
        String updatedBy,
        OffsetDateTime updatedAt) {

    public static ProcessDraft of(String tenantId, String key, String name, String bpmnXml,
                                  long revision, String updatedBy, OffsetDateTime updatedAt) {
        return new ProcessDraft(tenantId, key, name, bpmnXml, BpmnXmlHash.of(bpmnXml),
                revision, updatedBy, updatedAt);
    }

    public ProcessDraft next(String newName, String newXml, String by, OffsetDateTime at) {
        return of(tenantId, processDefinitionKey, newName, newXml, revision + 1, by, at);
    }
}
