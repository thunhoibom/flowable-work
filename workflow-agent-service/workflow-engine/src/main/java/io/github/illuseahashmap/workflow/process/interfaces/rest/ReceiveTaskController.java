package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.process.application.ReceiveTaskOperationService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessWaitView;
import io.github.illuseahashmap.workflow.process.application.dto.TriggerReceiveTaskRequest;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Manual wait steps of a running instance. */
@RestController
@RequestMapping("/workflow/management/process/instance")
public class ReceiveTaskController {

    private final ReceiveTaskOperationService receiveTasks;

    public ReceiveTaskController(ReceiveTaskOperationService receiveTasks) {
        this.receiveTasks = receiveTasks;
    }

    @GetMapping("/{processInstanceId}/waits")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')")
    public ApiResponse<List<ProcessWaitView>> waits(@PathVariable String processInstanceId) {
        return ApiResponse.ok(receiveTasks.waits(processInstanceId));
    }

    @PostMapping("/receive-task/trigger")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR')")
    public ApiResponse<Void> trigger(@Valid @RequestBody TriggerReceiveTaskRequest request) {
        receiveTasks.trigger(request);
        return ApiResponse.ok();
    }
}
