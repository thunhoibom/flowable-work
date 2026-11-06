package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.process.application.DraftPublishMigrationService;
import io.github.illuseahashmap.workflow.process.application.ProcessDraftService;
import io.github.illuseahashmap.workflow.process.application.dto.DraftPublishMigrationResult;
import io.github.illuseahashmap.workflow.process.application.ProcessPublishService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessReleaseView;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.RollbackProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.CreateProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftRevisionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDraftView;
import io.github.illuseahashmap.workflow.process.application.dto.SaveProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ActivateProcessVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ActiveProcessVersionResult;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionView;
import io.github.illuseahashmap.workflow.process.application.WorkflowDefinitionService;
import io.github.illuseahashmap.workflow.process.application.WorkflowAdministrationService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDiagramDataView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceDetailView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.TerminateProcessRequest;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/workflow/management/process")
public class WorkflowManagementController {

    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowAdministrationService administrationService;
    private final ProcessDraftService processDraftService;
    private final ProcessPublishService processPublishService;
    private final DraftPublishMigrationService draftPublishMigrationService;

    public WorkflowManagementController(WorkflowDefinitionService workflowDefinitionService,
                                        WorkflowAdministrationService administrationService,
                                        ProcessDraftService processDraftService,
                                        ProcessPublishService processPublishService,
                                        DraftPublishMigrationService draftPublishMigrationService) {
        this.workflowDefinitionService = workflowDefinitionService;
        this.administrationService = administrationService;
        this.processDraftService = processDraftService;
        this.processPublishService = processPublishService;
        this.draftPublishMigrationService = draftPublishMigrationService;
    }

    /** One-off conversion of the current tenant. Dry run unless {@code dryRun=false} is sent. */
    @PostMapping("/migrations/draft-publish")
    @PreAuthorize("hasRole('WF_PLATFORM_ADMIN')")
    public ApiResponse<DraftPublishMigrationResult> migrateDraftPublish(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return ApiResponse.ok(draftPublishMigrationService.run(dryRun));
    }

    @PostMapping("/{key}/publish")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')")
    public ApiResponse<PublishProcessResult> publish(@PathVariable String key,
                                                     @Valid @RequestBody PublishProcessRequest request) {
        return ApiResponse.ok(processPublishService.publish(key, request));
    }

    @PostMapping("/{key}/rollback")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')")
    public ApiResponse<PublishProcessResult> rollback(@PathVariable String key,
                                                      @Valid @RequestBody RollbackProcessRequest request) {
        return ApiResponse.ok(processPublishService.rollback(key, request));
    }

    @PostMapping("/{key}/suspend")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')")
    public ApiResponse<ProcessReleaseView> suspend(@PathVariable String key) {
        return ApiResponse.ok(processPublishService.suspend(key));
    }

    @PostMapping("/{key}/resume")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')")
    public ApiResponse<ProcessReleaseView> resume(@PathVariable String key) {
        return ApiResponse.ok(processPublishService.resume(key));
    }

    @GetMapping("/{key}/releases")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')")
    public ApiResponse<List<ProcessReleaseView>> releases(@PathVariable String key) {
        return ApiResponse.ok(processPublishService.releases(key));
    }

    @PostMapping("/drafts")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')")
    public ApiResponse<ProcessDraftView> createDraft(@Valid @RequestBody CreateProcessDraftRequest request) {
        return ApiResponse.ok(processDraftService.create(request));
    }

    @GetMapping("/drafts/{key}")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')")
    public ApiResponse<ProcessDraftView> draft(@PathVariable String key) {
        return ApiResponse.ok(processDraftService.get(key));
    }

    @PutMapping("/drafts/{key}")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')")
    public ApiResponse<ProcessDraftView> saveDraft(@PathVariable String key,
                                                   @Valid @RequestBody SaveProcessDraftRequest request) {
        return ApiResponse.ok(processDraftService.save(key, request));
    }

    @PostMapping("/drafts/{key}/load-version")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')")
    public ApiResponse<ProcessDraftView> loadVersionIntoDraft(@PathVariable String key,
                                                              @Valid @RequestBody DraftVersionRequest request) {
        return ApiResponse.ok(processDraftService.loadVersion(key, request));
    }

    @PostMapping("/drafts/{key}/discard")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')")
    public ApiResponse<ProcessDraftView> discardDraft(@PathVariable String key,
                                                      @Valid @RequestBody DraftRevisionRequest request) {
        return ApiResponse.ok(processDraftService.discard(key, request));
    }

    @PostMapping("/deploy")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')")
    public ApiResponse<DeployProcessResult> deploy(@Valid @RequestBody DeployProcessRequest request) {
        return ApiResponse.ok(workflowDefinitionService.deploy(request));
    }

    @PostMapping("/definition/activate")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')")
    public ApiResponse<ActiveProcessVersionResult> activate(@Valid @RequestBody ActivateProcessVersionRequest request) {
        return ApiResponse.ok(workflowDefinitionService.activate(request));
    }

    @GetMapping("/definition/active")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')")
    public ApiResponse<ActiveProcessVersionResult> active(@RequestParam String processDefinitionKey) {
        return ApiResponse.ok(workflowDefinitionService.getActiveVersion(processDefinitionKey));
    }

    @GetMapping("/definition/exists")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')")
    public ApiResponse<Boolean> exists(@RequestParam String processDefinitionKey) {
        return ApiResponse.ok(workflowDefinitionService.exists(processDefinitionKey));
    }

    @GetMapping("/definitions")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')")
    public ApiResponse<List<ProcessDefinitionView>> list(
            @RequestParam(required = false) String processDefinitionKey) {
        return ApiResponse.ok(workflowDefinitionService.list(processDefinitionKey));
    }

    @GetMapping("/definition")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')")
    public ApiResponse<ProcessDefinitionView> detail(@RequestParam String processDefinitionKey,
                                                     @RequestParam(required = false) Integer version) {
        return ApiResponse.ok(workflowDefinitionService.getDefinition(processDefinitionKey, version));
    }

    @GetMapping(value = "/diagram", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')")
    public ResponseEntity<byte[]> processDiagram(@RequestParam String processInstanceId) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(administrationService.generateProcessDiagram(processInstanceId));
    }

    @GetMapping("/diagram-data")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')")
    public ApiResponse<ProcessDiagramDataView> processDiagramData(@RequestParam String processInstanceId) {
        return ApiResponse.ok(administrationService.getProcessDiagramData(processInstanceId));
    }

    @GetMapping("/definitions/page")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')")
    public ApiResponse<PageResult<ProcessDefinitionSummaryView>> pageDefinitions(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) String processDefinitionName,
            @RequestParam(defaultValue = "all") String publishStatus) {
        return ApiResponse.ok(administrationService.pageProcessDefinitions(
                pageNum, pageSize, processDefinitionKey, processDefinitionName, publishStatus));
    }

    @GetMapping("/instances/page")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')")
    public ApiResponse<PageResult<ProcessInstanceSummaryView>> pageInstances(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) String processDefinitionName,
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String businessKey,
            @RequestParam(defaultValue = "all") String status) {
        return ApiResponse.ok(administrationService.pageProcessInstances(
                pageNum, pageSize, processDefinitionKey, processDefinitionName,
                processInstanceId, businessKey, status));
    }

    @GetMapping("/instance/detail")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')")
    public ApiResponse<ProcessInstanceDetailView> instanceDetail(@RequestParam String processInstanceId) {
        return ApiResponse.ok(administrationService.getProcessInstanceDetail(processInstanceId));
    }

    @PostMapping("/instance/terminate")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR')")
    public ApiResponse<Void> terminate(@Valid @RequestBody TerminateProcessRequest request) {
        administrationService.terminateProcessInstance(request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/definition")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')")
    public ApiResponse<Void> deleteDefinitions(@RequestParam String processDefinitionKey) {
        administrationService.deleteProcessDefinitions(processDefinitionKey);
        return ApiResponse.ok();
    }

    @DeleteMapping("/definition/version")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')")
    public ApiResponse<Void> deleteDefinitionVersion(@RequestParam String processDefinitionKey,
                                                     @RequestParam Integer version) {
        administrationService.deleteProcessDefinitionVersion(processDefinitionKey, version);
        return ApiResponse.ok();
    }
}
