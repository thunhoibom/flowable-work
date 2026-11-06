package io.github.illuseahashmap.workflow.process.application.dto;

import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritResult;
import java.util.List;

/**
 * @param rules          rules copied from the previous version; {@code null} for the first version
 * @param newUserTaskIds user tasks that the previous version did not have
 */
public record PublishProcessResult(
        String deploymentId,
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        int version,
        AssignmentRuleInheritResult rules,
        List<String> newUserTaskIds) {

    public PublishProcessResult {
        newUserTaskIds = List.copyOf(newUserTaskIds);
    }
}
