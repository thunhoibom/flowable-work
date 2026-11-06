package io.github.illuseahashmap.workflow.process.application.dto;

/** A compact reference to another instance in the same call tree, for breadcrumbs. */
public record ProcessInstanceLinkView(
        String processInstanceId,
        String processDefinitionKey,
        String processDefinitionName,
        String businessKey,
        String status) {
}
