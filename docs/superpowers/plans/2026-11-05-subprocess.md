# Subprocess Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Designers can group steps in embedded and event subprocesses and call other published processes through call activities. Operators can see and safely act on the parent–child instance tree.

**Spec:** `docs/superpowers/specs/2026-11-05-subprocess-design.md`

**Depends on:** `docs/superpowers/plans/2026-11-05-draft-publish.md` must be complete before Phase 2. Phase 1 can run in parallel with it.

**Architecture:**
- Stock Flowable behaviors for subprocess and call activity. No behavior is replaced.
- The platform fixes the parts of its own code that assume a flat process: the path resolver, the user-task assignment check and the designer sanitizer.
- `WorkflowSubProcessStartInterceptor` adds the platform rules at the official hook:
  - `before`: runtime call-depth limit
  - `after`: trusted tenant variables and start-contract validation
- `CallActivityDeploymentGuard` runs inside the publish pipeline. It writes `workflow_call_reference`, which drives cycle detection, impact analysis and "where used".
- Process-instance locking moves to the root instance id.

**Tech Stack:** Java 25, Spring Boot 4.1, Flowable 8.0.0, JUnit 5, AssertJ, Mockito, H2 (test only), PostgreSQL + Flyway, Vue 3, Vitest, Element Plus, bpmn-js 18.

**Granularity:** Task level. Before executing a phase, expand its tasks into code-level TDD steps, the same way `2026-11-04-script-task-node.md` does. Each phase ships on its own.

## Global Constraints

- Flowable stays `8.0.0`. No platform-specific BPMN attributes on subprocess or call activity. No replacement of `SubProcessActivityBehavior` or `CallActivityBehavior`.
- Call activities resolve by key to the latest definition in the tenant. After draft-publish, that is always the published one. A suspended callee fails at the call activity, which is stock behavior.
- The interceptor wraps any existing `StartProcessInstanceInterceptor`, never replaces it. Variable rules live only in `afterStartSubProcessInstance`, because `<flowable:in>` is applied after `before`.
- Rejected at publish: `calledElementType="id"`, `calledElement` containing `${` or `#{`, `inheritVariables`, `sameDeployment`, `calledElementBinding`, `fallbackToDefaultTenant`, a callee that is not published, `in`/`out` targets matching `ProcessVariablePolicy.isInternalVariable`, a parallel multi-instance call activity without `completeAsync="true"`, call cycles, and static call depth above `max-call-depth`.
- `tenantId`/`tenantCode` in a child always equal the parent's values.
- Guard failures throw `BusinessException(ErrorCode.BAD_REQUEST, ...)`. Runtime failures throw `CallActivityException extends FlowableException`. Messages never contain variable values.
- Guard order inside `ProcessDeployer.deployChecked` (from the draft-publish plan): size → script guard → subprocess guard → call activity guard → Agent validator → `createDeployment`. `ProcessPublishServiceImpl` then writes `workflow_call_reference`, copies rules and writes the release row.
- Migration `V39__workflow_call_reference.sql` (V38 belongs to draft-publish). RLS follows V16.
- Properties prefix `workflow.subprocess`. Defaults: `call-activity-enabled=false`, `max-nesting-depth=3`, `max-call-depth=5`, `max-io-parameters=64`.
- Web: the call activity palette entry shows only when `VITE_CALL_ACTIVITY_ENABLED=true`. A draft save always succeeds. Only publish enforces the guards.
- Out of scope: ad-hoc, transaction, pinning an old callee version, dynamic injection, instance migration, message/signal (P4).

Commands for Java tasks run from `workflow-agent-service`. Web commands run from `workflow-agent-web`.

---

## Phase 1 — Embedded and event subprocess

### Task 1.1: Pin down engine behavior with tests

**Files:**
- Create: `workflow-engine/src/test/java/.../process/infrastructure/flowable/SubProcessEngineTest.java`
- Create: `workflow-engine/src/test/resources/bpmn/subprocess/*.bpmn20.xml`

- [x] Use an in-memory H2 engine, set up like `WorkflowScriptTaskEngineTest`.
- [x] Cover:
  - local variables stay in the subprocess scope
  - parallel multi-instance with `completionCondition`
  - an interrupting boundary timer cancels every inner execution
  - an error event subprocess catches a `BpmnError` from an inner delegate
  - a subprocess without a none start event fails
  - bpmn-js XML with a collapsed subprocess (several `BPMNDiagram` planes) deploys and keeps its DI
- [x] Acceptance: all tests pass on `8.0.0`, with no production code change.

### Task 1.2: Path resolver understands nested scopes

**Files:**
- Modify: `workflow-engine/src/main/java/.../process/infrastructure/flowable/FlowableUserTaskPathResolver.java`
- Modify/Create: `FlowableUserTaskPathResolverTest.java`

- [x] Look up elements through `FlowElementsContainer` / `model.getFlowElement(id)`.
- [x] `SubProcess` (not event-triggered): enqueue its none start event. End event inside: continue along the subprocess's outgoing flows.
- [x] Skip event subprocesses. Stop at `CallActivity`.
- [x] Tests:
  - a user task inside a subprocess is the next step
  - the step after a subprocess that holds only automatic tasks is found
  - a gateway inside a subprocess
  - nesting at depth 2
  - traversal stops at a call activity

### Task 1.3: Subprocess publish guard

**Files:**
- Create: `config/SubProcessProperties.java`
- Create: `process/infrastructure/flowable/SubProcessDeploymentGuard.java` + test
- Modify: `ProcessDeployer.deployChecked` (or `WorkflowDefinitionServiceImpl.deploy` if Phase 1 lands before draft-publish), `application.yml`, `.env.example`

- [x] Parse with secure XML settings, the same as `XmlServiceTaskBindingParser`.
- [x] Reject:
  - a subprocess without exactly one none start event, or without an end event
  - nesting above `max-nesting-depth`
  - an event subprocess with incoming or outgoing flows, or with a start that is not error or timer
  - a boundary on a subprocess that is not timer or error
  - `adHocSubProcess`, `transaction`
- [x] Run it after the script guard. Test the order with Mockito `inOrder`.

### Task 1.4: Designer — embedded subprocess

**Files:**
- Modify: `src/bpmn/workflowPaletteModule.ts`, `src/features/process-definition/views/ProcessDesignerView.vue`
- Create: `components/SubProcessConfigPanel.vue`, `subProcessPanel.ts`, `subProcessPanel.spec.ts`
- Modify: `src/__tests__/bpmn.spec.ts`

- [x] Palette "Thêm quy trình con". Remove transaction and ad-hoc from the palette and the replace menu.
- [x] Collapse/expand through the replace menu. Drill down with breadcrumbs (bpmn-js 18 built-in).
- [x] `sanitizeModel`, `ensureAllUserTaskAssignments` and the Agent normalizers walk every plane. Test that a user task inside a collapsed subprocess keeps its assignment after save.
- [x] Panel: multi-instance. Event subprocess: start type (error/timer), error code or timer value, interrupting flag.
- [x] `bpmn.spec.ts`: a nested subprocess with a boundary timer and multi-instance survives export.

### Task 1.5: Instance diagram for nested scopes

**Files:**
- Modify: `src/features/process-instance/components/ProcessDiagram.vue`, `WorkflowInstanceReadService.java` if needed

- [x] Highlight activities inside subprocesses. A collapsed subprocess that contains an active activity gets a marker, and drilling down shows the highlighted task.

**Phase 1 exit:** `mvn verify` and `pnpm check` pass, and a nested-subprocess model publishes and runs on the dev stack.

---

## Phase 2 — Call activity (after draft-publish)

### Task 2.1: Engine facts for call activities

**Files:**
- Create: `CallActivityEngineFactsTest.java` (stock behavior only)

- [x] Prove on 8.0.0:
  - resolves the latest in the tenant
  - a suspended callee fails at the call activity
  - an unhandled `BpmnError` in a child is caught by an error boundary on the call activity
  - deleting the parent deletes the child
  - `useLocalScopeForOutParameters`
  - a parallel multi-instance call activity with 10 children and `completeAsync=true` completes (run the async executor)
- [x] These tests guard the assumptions when Flowable is upgraded.

### Task 2.2: Start interceptor

**Files:**
- Create: `process/infrastructure/flowable/subprocess/WorkflowSubProcessStartInterceptor.java`
- Create: `process/infrastructure/flowable/subprocess/CallActivityException.java`
- Modify: `config/FlowableEngineConfig.java`
- Create: `WorkflowSubProcessStartInterceptorTest.java` (H2 engine)

- [x] Register through the existing `ProcessEngineConfigurationConfigurer`. If `getStartProcessInstanceInterceptor()` is already set, delegate to it.
- [x] `beforeStartSubProcessInstance`: walk `superExecutionId` from `getCallActivityExecution()` up to the root, and throw above `max-call-depth`.
- [x] `afterStartSubProcessInstance`:
  - set `tenantId`/`tenantCode` on `getSubProcessInstance()` from the parent instance
  - call `WorkflowInteractionGuard.validateStart` with `getVariables()` plus the tenant variables
- [x] Non-subprocess starts (`beforeStartProcessInstance` / `afterStartProcessInstance`) pass straight through.
- [x] Tests: every interceptor case in the spec. Deploy straight to the engine to bypass the guard when testing the override defense.

### Task 2.3: Reference table

**Files:**
- Create: `platform-migrations/.../V39__workflow_call_reference.sql`
- Create: `process/application/port/CallReferenceRepository.java`, `process/infrastructure/persistence/JdbcCallReferenceRepository.java` + test

- [x] The table, index and RLS from the spec.
- [x] Methods: `replaceForDefinition`, `deleteForDefinition`, `findPublishedCallers(key)` (joins against the latest non-suspended definition per parent key), `findCallees(definitionId)`, `publishedEdges()`.

### Task 2.4: Call activity publish guard

**Files:**
- Create: `process/infrastructure/flowable/subprocess/CallActivityDeploymentGuard.java` + test
- Modify: `ProcessDeployer.java` (guard), `ProcessPublishServiceImpl.java` (reference rows) (+ tests), `WorkflowAdministrationServiceImpl.java` (delete paths)

- [x] `check(tenant, key, bpmnXml)` returns the references. It enforces every publish rule in the spec, including the flag and "callee is published".
- [x] Cycle detection: start from `publishedEdges()`, replace this key's edges with the new references, and run a DFS from this key. Report the cycle path, for example `A → B → A`. Compute the longest chain against `max-call-depth`.
- [x] Callee assignment check: run `FlowableParticipantAssignmentCoordinator.requirementsForStart` on the callee's published definition with empty variables. Any requirement means rejection, naming the callee key and task id.
- [x] After `createDeployment`, call `replaceForDefinition`. Delete paths call `deleteForDefinition`. Deleting a key is rejected while `findPublishedCallers` is not empty.

### Task 2.5: Lock on the root instance

**Files:**
- Modify: `process/infrastructure/lock/ProcessInstanceTransactionExecutor.java` (+ test)

- [x] Resolve the root through `RuntimeService` (fall back to the given id when the instance is finished) and lock on it. Put `rootProcessInstanceId` in MDC.
- [x] Test with a fake lock that records keys.
- [x] Callers (`WorkflowRuntimeServiceImpl` ×4, `WorkflowAdministrationServiceImpl`, `AssignmentFallbackExecutor` ×2) need no change.

### Task 2.6: Designer — call activity

**Files:**
- Modify: `src/bpmn/flowableModdle.ts`, `src/bpmn/workflowPaletteModule.ts`, `src/config/product.ts`, `env.d.ts`, `.env.example`
- Create: `components/CallActivityConfigPanel.vue`, `callActivityPanel.ts`, `callActivityPanel.spec.ts`
- Modify: `ProcessDesignerView.vue`, `features/process-definition/api.ts`, `src/__tests__/bpmn.spec.ts`

- [x] Moddle: `CallActivity` attributes, the `Out` type, and `transient` on `In`/`Out`.
- [x] `callActivityPanel.ts` (pure, tested):
  - rows ↔ `flowable:In`/`flowable:Out` (`sourceExpression` when the value contains `${`/`#{`)
  - force `completeAsync` under parallel multi-instance
  - warnings that mirror the publish rules
- [x] Panel:
  - picker of published processes, showing the running version
  - in/out tables
  - "Gợi ý từ quy trình con" (from the callee's start interaction)
  - business key mode, id variable, "Mở quy trình được gọi"
- [x] Palette "Gọi quy trình khác" behind `callActivityEnabled`. Label "Gọi quy trình".

**Phase 2 exit:** `mvn verify` and `pnpm check` pass. On the dev stack:
- a parent calls a child
- saving a child draft does not change what the parent calls
- publishing the child changes it for new calls only
- suspending the child makes the call fail clearly
- a cycle is rejected with a readable path

---

## Phase 3 — Operations

### Task 3.1: Hierarchy read APIs

**Files:**
- Modify: `WorkflowInstanceReadService.java`, `WorkflowManagementController.java`, `ProcessInstanceDetailView.java`, `ProcessInstanceSummaryView.java`, `TaskView.java`, `TaskViewAssembler.java` (+ tests)

- [x] `rootOnly` filter (`excludeSubprocesses(true)`) and a parent column.
- [x] `children(id)` by `superProcessInstanceId`, with the call activity id taken from the super execution.
- [x] `hierarchy(id)`: walk up to the root, capped at `max-call-depth + 1`.
- [x] New fields on the detail view and `TaskView`. Every query filters by tenant.

### Task 3.2: References APIs and impact analysis

**Files:**
- Modify: `WorkflowDefinitionReadService.java`, `WorkflowManagementController.java`, `ProcessPublishServiceImpl` (publish and suspend results) (+ tests)

- [x] `GET /process-definitions/{key}/callers`, `GET /process-definitions/{key}/callees?version=`.
- [x] Publish result `warnings[]`:
  - required start inputs of the new version that a caller does not map
  - variables a caller reads via `out` that the new version never writes (detected statically from script `resultVariable`, service output mappings and form fields; otherwise a softer warning)
- [x] Suspend result lists published callers. The UI asks for confirmation before calling suspend.

### Task 3.3: Safety rails in administration

**Files:**
- Modify: `WorkflowAdministrationServiceImpl.java` (+ test)

- [x] Terminate: if the instance has a super execution, throw `CONFLICT` with the root id. Terminating a root audits each descendant with `PROCESS_TERMINATED` ("cascade from <root>").

### Task 3.4: Subprocess audit listener

**Files:**
- Create: `process/infrastructure/flowable/subprocess/SubProcessAuditListener.java` (+ test)
- Modify: `FlowableEngineConfig.java`

- [x] On `PROCESS_STARTED` / `PROCESS_COMPLETED` / `PROCESS_COMPLETED_WITH_ERROR_END_EVENT` / `PROCESS_CANCELLED` with a super execution, record `SUBPROCESS_*` with the parent and root ids. Handle failures the same way the existing audit service does.

### Task 3.5: Instance and definition UI

**Files:**
- Modify: `ProcessInstanceListView.vue`, `ProcessInstanceDetailView.vue`, `ProcessDiagram.vue`, `process-instance/api.ts`, `types.ts`, `ProcessDefinitionListView.vue`, `ProcessDesignerView.vue`, `src/api/queryKeys.ts`
- Create: `process-instance/hierarchy.ts`, `processInstanceHierarchy.spec.ts`

- [x] List: root-only by default, a "Hiện cả quy trình con" switch, and a parent link.
- [x] Detail: breadcrumb, a "Quy trình con" tab, and clicking a call activity opens its child (or a chooser for multi-instance).
- [x] Inbox and task views: show the root business key and process name.
- [x] Definitions: "Được dùng ở đâu" / "Gọi tới". Publish, suspend and delete confirmations show the impact analysis.
- [x] Vietnamese text per `2026-10-28-vietnamese-ui-design.md`.

**Phase 3 exit:** `mvn verify` and `pnpm check` pass. On the dev stack:
- start a parent, complete a child task, and navigate the tree
- terminating the child is rejected
- terminating the root removes the tree, with every node audited
- deleting a called process is rejected

---

## Phase 4 — Optional follow-ups (separate specs)

- Message/signal start events and boundaries, once a correlation API exists.
- Participant selection across a call activity boundary.
- `calledElement` expressions with an allowlist.
- Process instance migration.

---

## Final verification (each phase)

- [ ] `mvn verify` from `workflow-agent-service`: BUILD SUCCESS, including SpotBugs, JaCoCo and ArchUnit (`DomainArchitectureTest`: ports in `application/port`, adapters in `infrastructure`).
- [ ] `pnpm check` from `workflow-agent-web`: PASS.
- [ ] Update the spec's "Câu hỏi còn mở".
- [ ] Commit per task (`feat:`, `test:`, `chore:`).
