package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * @param ancestors      the call chain above this instance, root first; empty for a root instance
 * @param callActivityId the parent's call activity that started this instance, if any
 * @param children       instances started by this instance's call activities
 */
public record ProcessInstanceDetailView(
        ProcessInstanceSummaryView instance,
        List<TaskItem> tasks,
        List<VariableItem> variables,
        List<ProcessInstanceLinkView> ancestors,
        String callActivityId,
        List<ChildProcessInstanceView> children
) {

    public record TaskItem(
            String taskId,
            String taskDefinitionKey,
            String taskName,
            String assignee,
            List<String> candidateUsers,
            List<String> candidateGroups,
            String status,
            String deleteReason,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            Long durationInMillis
    ) {
    }

    public record VariableItem(
            String variableId,
            String variableName,
            String variableTypeName,
            Object value,
            String executionId,
            String taskId,
            OffsetDateTime createTime,
            OffsetDateTime lastUpdatedTime,
            String scopeId,
            String subScopeId,
            String scopeType
    ) {
    }
}
