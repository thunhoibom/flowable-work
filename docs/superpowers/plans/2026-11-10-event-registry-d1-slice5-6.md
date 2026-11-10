# Event Registry D1 — Slices 5–6 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- **Slice 5:** a designer can wait for an **event** on the diagram in three places:
  - a receive task in "Chờ sự kiện" mode (Receive Event Task);
  - an intermediate catch event without event definition (Intermediate Event Registry Catch);
  - a boundary event without event definition (Boundary Event Registry), interrupting or not.

  Each one picks a published event, gives a correlation expression for each correlation field, and maps event fields to variables. Publishing validates all of this against the tenant's library and records which process version uses which event (`workflow_event_reference`).
- **Slice 6:** operators see event waits:
  - in the instance's "Đang chờ" block, with the current correlation values and a warning when they changed after the wait started;
  - in a tenant-wide "Đang chờ" tab per event;
  - in an "Đang được dùng ở" list on each event model.

**Spec:** `docs/superpowers/specs/2026-11-09-event-registry-d1-design.md`, sections "Bước chờ trên sơ đồ", "Quy trình (bổ sung vào BpmnDraftInspector)", "Xem các bước đang chờ", "Designer", "Màn hình", "Kiểm tra → Lát 5/6". Risk ids R-xx: `2026-11-09-receive-task-research.md`. Earlier plans: `2026-11-09-event-registry-d1-slice1-2.md`, `2026-11-09-event-registry-d1-slice3-4.md`.

**Architecture:**
- **Publish side, in `process.infrastructure.flowable`:**
  - `EventWaitBindingParser` reads the event bindings from the XML.
  - `EventRegistryDeploymentGuard` validates them against the tenant library through `EventRegistryGateway` (slice 3 port).
  - `ProcessDeployer` writes them to `workflow_event_reference` through a new `EventReferenceRepository` port, next to `workflow_call_reference`.
- **Runtime side:** no change. Flowable's own behaviors do the waiting. The slice 4 webhook and `WorkflowBpmnEventRegistryEventConsumer` do the matching.
- **Read side (slice 6):**
  - `ReceiveTaskOperationServiceImpl#waits` also returns event waits.
  - Current correlation values come from a read-only Flowable `Command`, `CorrelationProbe`.
  - Tenant-wide waits and usages live in `eventregistry` (`EventWaitQueryService`).
- **Web:**
  - Moddle already knows `EventType`, `EventCorrelationParameter` and `EventOutParameter` (slice 2).
  - New `bpmn/eventWaitModel.ts` holds the read, write and warning helpers.
  - New `EventWaitConfigPanel.vue` serves all three elements.
  - `ReceiveTaskConfigPanel.vue` gets the manual/event mode switch.
  - Palette and replace menu gain entries.

**Tech Stack:** as slices 3–4. Flowable 8.0.0 sources are already extracted in the scratchpad (`flsrc`, plus `conv` for `flowable-bpmn-converter`).

## Global Constraints

- Do **not** commit (user decision, 2026-11-09). Skip every commit step and list the changed files instead.
- Append only in files a teammate may be editing: `application.yml`, both `.env.example`, `src/config/product.ts`.
- **Flag:**
  - `workflow.event-registry.enabled=false`: publishing any diagram with an event wait fails with `BAD_REQUEST "Bước X chờ sự kiện nhưng tính năng sự kiện chưa được bật"`.
  - Web, `eventsEnabled=false`: no palette entry, no replace entry, no event mode. An imported event wait stays visible as read-only, with that warning.
  - Manual waits never depend on the flag.
- Event key on the diagram is fixed text (no `${`/`#{`) and matches `^[A-Za-z][A-Za-z0-9_-]{0,63}$`.
- Correlation:
  - at least one `eventCorrelationParameter` (R-B1);
  - every `name` is a correlation field of the published event, with no duplicates;
  - every `value` is non-blank.
- Outputs:
  - every `eventOutParameter.source` is a field of the event;
  - `target` matches `^[A-Za-z_][A-Za-z0-9_]{0,127}$`, is not `ProcessVariablePolicy.isInternalVariable`, and has no duplicates (R-F3).
- Still rejected, as later phases:
  - `startEvent` with `flowable:eventType` (Đ2);
  - any `triggerEventType` / `triggerEventCorrelationParameter` (Đ4);
  - `serviceTask flowable:type="send-event"` (Đ4).
- Rejected for good:
  - a receive task that is both Agent and event (R-H2);
  - a receive task after an event-based gateway (R-H1);
  - an intermediate or boundary event that has both an event definition and `flowable:eventType`.
- New wait steps default to `flowable:asyncLeave="true"` (R-D1). This includes intermediate and boundary events, because Flowable reads `asyncLeave` on every `FlowNode` (`BaseBpmnXMLConverter#parsAsyncLeave`).
- **`CorrelationProbe` safety:**
  - It never evaluates an expression that could have side effects. Only plain variable paths matching `^\$\{[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*\}$` are evaluated.
  - Other expressions are shown as "không tính lại", and the change check is skipped for that wait.
- Logs never contain correlation values or payload. API responses may show current correlation values to readers, as the spec's "giá trị hiện tại".
- Messages are Vietnamese and name the element id: `Bước <id>: …`.

Java commands run from `workflow-agent-service`, web commands from `workflow-agent-web`. Maven: the wrapper distribution under `~/.m2/wrapper/dists` with `-o`, and `-pl workflow-engine -am` for engine tests.

---

## Slice 5 — Event waits on the diagram

### Task 1: Pin Flowable 8.0.0 behaviour the slice relies on

**Files:**
- Modify: `workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/EventRegistryEngineFactsTest.java`

Add facts. Each one is a separate process key in the same in-memory engine:

- [ ] **Step 1: Intermediate catch.** `<intermediateCatchEvent id="wait">` with no event definition, plus the three extensions. It creates one subscription with `activityId = wait`. A matching event moves the instance on and maps `amount → paidAmount`.
- [ ] **Step 2: Boundary, interrupting.** A `userTask` with `<boundaryEvent attachedToRef="work" cancelActivity="true">` carrying the extensions. A matching event cancels the user task and takes the boundary path.
- [ ] **Step 3: Boundary, non-interrupting.** `cancelActivity="false"`. After a matching event the user task is still there and the boundary path also ran.
- [ ] **Step 4: Event-based gateway.** An event-based gateway with an event-registry intermediate catch on one branch and a timer catch (`PT1H`) on the other. The event takes its branch and the timer job is gone. This confirms the alternative the guard suggests for receive tasks (R-H1).
- [ ] **Step 5: Timer first.** A receive event task with a timer boundary `PT1S`, interrupting. Execute the timer job with `managementService.moveTimerToExecutableJob` + `executeJob`. Then send the event: no subscription is left, and the non-matching consumer is called. Assert through an `InboundDispatchContext`: `NO_MATCH`.
- [ ] **Step 6: Text versus number.** Correlation `${orderId}` with variable `orderId = 123` (Integer). An event whose `orderId` string field is `"123"` matches. Explanation: `DefaultCorrelationKeyGenerator` uses `toString()`.
- [ ] **Step 7: asyncLeave on events.** An intermediate catch with `flowable:asyncLeave="true"`. After the event, the instance is still at the catch, with one async job. Running the job moves it on.
- [ ] **Step 8: Run.** `mvn -q -o -pl workflow-engine -am test -Dtest=EventRegistryEngineFactsTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true`. Expected: PASS.

If a fact fails, stop and report it before changing the guard or designer rules that depend on it.

---

### Task 2: Event binding parser

**Files:**
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/EventWaitBinding.java`
- Create: `…/process/infrastructure/flowable/EventWaitBindingParser.java`
- Test: `workflow-engine/src/test/java/…/process/infrastructure/flowable/EventWaitBindingParserTest.java`

**Interfaces:**

```java
public record EventWaitBinding(
        String activityId,
        Kind kind,                       // RECEIVE_TASK, INTERMEDIATE_CATCH, BOUNDARY
        String eventKey,                 // trimmed text of flowable:eventType
        List<Correlation> correlations,  // in document order
        List<Output> outputs,
        boolean hasEventDefinition,      // only for INTERMEDIATE_CATCH / BOUNDARY
        boolean agentTask,               // only for RECEIVE_TASK
        String attachedToRef) {          // only for BOUNDARY
    public enum Kind { RECEIVE_TASK, INTERMEDIATE_CATCH, BOUNDARY }
    public record Correlation(String name, String value) { }
    public record Output(String source, String target, boolean transientValue) { }
}
```

`EventWaitBindingParser.parse(String xml): List<EventWaitBinding>`:
- DOM, same secure factory settings as `EventRegistryDeploymentGuard#parse`.
- Every `receiveTask`, `intermediateCatchEvent` and `boundaryEvent` whose `extensionElements` contains a `flowable:eventType` (or `activiti:`), even with a blank body, so blank keys are reported.
- Attributes are read without a namespace (`name`, `value`, `source`, `target`), as Flowable's `CorrelationUtil` does. `transient` is `"true"` case-insensitive.

- [ ] **Step 1: Tests first:**
  - The three kinds are found, with correlations and outputs in order.
  - An element without `eventType` is ignored.
  - A blank `eventType` is returned with `eventKey = ""`.
  - `hasEventDefinition` is true for a boundary event with `timerEventDefinition` and an `eventType`.
  - `agentTask` is detected.
- [ ] **Step 2: Implement, run, PASS.**

---

### Task 3: Deploy guard validates event waits

**Files:**
- Modify: `…/process/infrastructure/flowable/EventRegistryDeploymentGuard.java`
- Modify: `…/process/infrastructure/flowable/ProcessDeployer.java` (call the new signature)
- Modify: `…/process/infrastructure/flowable/SubProcessDeploymentGuard.java` (allow event-registry boundaries on embedded subprocesses)
- Modify: `…/process/application/ProcessVariablePolicy.java` only if `isInternalVariable` is not public (it is public today, so no change is expected)
- Test: `workflow-engine/src/test/java/…/process/infrastructure/flowable/EventRegistryDeploymentGuardTest.java` (rewrite)
- Test: update `ProcessPublishServiceImplTest` and any test that constructs `EventRegistryDeploymentGuard` or `ProcessDeployer`

**Interfaces:**
- `public List<EventWaitBinding> check(TenantContext.TenantInfo tenant, String bpmnXml)`. It returns the bindings that passed, so the deployer can record them.
- Constructor: `EventRegistryDeploymentGuard(EventRegistryProperties properties, EventRegistryGateway gateway)`.

Rules, in this order. The first violation throws `BusinessException(BAD_REQUEST, …)`:

| # | Check | Message |
|---|---|---|
| 1 | Any `triggerEventType`/`triggerEventCorrelationParameter`, `startEvent` with `eventType`, `serviceTask flowable:type="send-event"` | `Bước X dùng tính năng sự kiện của đợt sau (…), chưa được hỗ trợ` |
| 2 | Receive task after event-based gateway (existing check) | unchanged |
| 3 | `RECEIVE_TASK` with `agentTask` | `Bước X không thể vừa là tác vụ Agent vừa chờ sự kiện` (unchanged) |
| 4 | Flag off and at least one binding | `Bước X chờ sự kiện nhưng tính năng sự kiện chưa được bật` |
| 5 | `hasEventDefinition` | `Bước X không được vừa có định nghĩa sự kiện vừa chờ sự kiện Event Registry` |
| 6 | `eventKey` blank, or contains `${`/`#{`, or does not match the key pattern | `Bước X: mã sự kiện phải là mã cố định hợp lệ` |
| 7 | `gateway.publishedEventFields(tenantId, key)` empty | `Bước X: sự kiện K chưa được phát hành` |
| 8 | No correlations | `Bước X phải có ít nhất một trường tương quan` |
| 9 | Correlation name not a correlation field / duplicate / blank value | `Bước X: sự kiện K không có trường tương quan N` · `Bước X: trường tương quan N bị lặp` · `Bước X: trường tương quan N chưa có giá trị` |
| 10 | Output source not a field / bad, internal or duplicate target | `Bước X: sự kiện K không có trường S` · `Bước X: tên biến T không hợp lệ` · `Bước X: biến T do hệ thống quản lý` · `Bước X: biến T được gán hai lần` |

Report every problem of the document in one message (`; `-joined, as `EventModelRules` does). Exceptions: rules 1–3 keep throwing on the first hit, as today.

`SubProcessDeploymentGuard`: a `boundaryEvent` attached to an embedded subprocess passes when it has no event definition and has a `flowable:eventType` child. Timer and error boundaries are unchanged. Other definitions are still rejected.

- [ ] **Step 1: Tests first** (`EventRegistryDeploymentGuardTest`, gateway mocked with Mockito):
  - Flag off: rejected with "chưa được bật". Flag on, valid receive task, intermediate and boundary: accepted, returns 3 bindings.
  - One test per row 5–10, asserting the message, with all problems reported together.
  - The slice 1–2 cases still hold: start event, trigger, send-event, Agent plus event, receive task after event-based gateway.
  - A manual receive task returns no binding, with the flag off and with it on.
- [ ] **Step 2: `SubProcessDeploymentGuardTest`:** an event-registry boundary on an embedded subprocess is accepted, and a message boundary is still rejected.
- [ ] **Step 3: Implement, wire into `ProcessDeployer#deployChecked`** (`eventRegistryDeploymentGuard.check(tenant, xml)`), run the engine tests. Expected: PASS.

---

### Task 4: Record and use `workflow_event_reference`

**Files:**
- Rename and extend: `…/eventregistry/application/port/EventReferenceReader.java` → `EventReferenceRepository.java`
- Rename and extend: `…/eventregistry/infrastructure/JdbcEventReferenceReader.java` → `JdbcEventReferenceRepository.java`
- Create: `…/eventregistry/domain/EventReference.java` (`processDefinitionId, activityId, eventKey`)
- Modify: `ProcessDeployer.java` (write references after deploy, as call references)
- Modify: `WorkflowAdministrationServiceImpl.java` (delete references where it deletes call references: `deleteForKey`, `deleteForDefinition`)
- Modify: `EventModelServiceImpl.java`, test fakes in `InMemoryEventRegistryRepositories`
- Test: extend `workflow-boot/src/test/java/…/eventregistry/EventRegistryPersistenceIntegrationTest.java`

**Interfaces:**

```java
public interface EventReferenceRepository {
    void replaceForDefinition(String tenantId, String processDefinitionId, List<EventReference> references);
    void deleteForDefinition(String tenantId, String processDefinitionId);
    /** Every version of the key, found through act_re_procdef. */
    void deleteForProcessKey(String tenantId, String processDefinitionKey);
    long latestProcessDefinitionsUsing(String tenantId, String eventKey);
    /** Latest process versions that use the event: key, version, activity ids. */
    List<EventUsage> usages(String tenantId, String eventKey);
    record EventUsage(String processDefinitionKey, int version, String activityId) { }
}
```

`deleteForProcessKey` uses:

```sql
DELETE FROM workflow_event_reference r USING act_re_procdef d
WHERE r.tenant_id = ? AND d.id_ = r.process_definition_id AND d.key_ = ? AND d.tenant_id_ = ?
```

It must run **before** the deployments are deleted in `WorkflowAdministrationServiceImpl`.

`usages` uses the same "latest version" join as `latestProcessDefinitionsUsing`, `ORDER BY key_, activity_id`.

- [ ] **Step 1: Wire:**
  - `ProcessDeployer#deployChecked` calls `eventReferences.replaceForDefinition(tenantId, definition.getId(), bindings → EventReference)`.
  - Rollback deploys go through `deployChecked`, so they are covered too.
- [ ] **Step 2: Integration test** (Postgres, flag on, tenant and principal contexts as in slice 4):
  - Publish event `refEvent`.
  - Publish a process through `ProcessPublishService#publishXml` that waits for `refEvent`. Expected: one reference, and `usages` returns it.
  - `EventModelService#delete("refEvent")` fails with `Quy trình đang dùng sự kiện này`.
  - Publish a new process version without the wait. Expected: `latestProcessDefinitionsUsing` is 0, and the delete still fails while an instance of the old version waits (`Còn 1 công việc`).
  - Delete the process key through `WorkflowAdministrationService` once nothing runs. Expected: no reference rows left.
- [ ] **Step 3: Run.** `mvn -o -pl workflow-boot -am test -Dtest=EventRegistryPersistenceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true`. Expected: PASS.

---

### Task 5: Runtime behaviour through the webhook

**Files:**
- Test: `workflow-engine/src/test/java/…/eventregistry/infrastructure/InboundEventReceiverImplTest.java` (extend), or a new `EventWaitRuntimeTest` that reuses `EventRegistryTestEngine`

These are the spec's "Lát 5" runtime checks. They run on the in-memory engine with the real receiver, so the guard is not involved. Deploy with `RepositoryService` directly.

- [ ] **Step 1:** Two instances wait on the same event with different correlation values. One event triggers exactly one of them (`MATCHED`, one delivery).
- [ ] **Step 2:** `"123"` in the body matches an instance whose `orderId` variable is `123`. A body with `"orderId": null` gets `422 INVALID_CORRELATION`.
- [ ] **Step 3 (R-D2):** Two instances wait with the **same** correlation, and their receive tasks have **no** `asyncLeave`. The step after the receive task is an exclusive gateway whose only outgoing condition is `${fail == false}`. Instance B has `fail = true`. Expected: `200 PARTIAL`, A moved on, B still waits, and `failed[0].errorCode = EVENT_PROCESSING_FAILED`.
- [ ] **Step 4:** Timer boundary fired first (as Task 1 step 5). The webhook then returns `409 NO_WAITING_INSTANCE`.
- [ ] **Step 5:** An interrupting event-registry boundary on a user task, reached through the webhook: the task is cancelled. A non-interrupting one: the task stays.
- [ ] **Step 6:** Intermediate catch through the webhook: `MATCHED`, and the variable is mapped.
- [ ] **Step 7: Run.** Expected: PASS.

---

### Task 6: Moddle and round-trip (web)

**Files:**
- Modify: `src/bpmn/flowableModdle.ts`: `AsyncLeave.extends` becomes `['bpmn:Activity', 'bpmn:IntermediateCatchEvent', 'bpmn:BoundaryEvent']`
- Test: `src/bpmn/eventWaitRoundTrip.spec.ts` (pattern of `subProcessRoundTrip.spec.ts`)

- [ ] **Step 1: Round-trip spec.** XML with:
  - a receive event task (with `asyncLeave`, two correlations, an output with `transient="true"`);
  - an intermediate catch with `asyncLeave`;
  - an interrupting boundary on a user task.

  `fromXML` → `toXML` → `fromXML`. Assert every `flowable:eventType` body, correlation `name`/`value`, output `source`/`target`/`transient`, `asyncLeave`, and `cancelActivity` survive.
- [ ] **Step 2: Implement and run** `npx vitest --run src/bpmn`. Expected: PASS.

---

### Task 7: Event wait model helpers (web)

**Files:**
- Create: `src/bpmn/eventWaitModel.ts`
- Test: `src/bpmn/eventWaitModel.spec.ts`
- Modify: `src/bpmn/waitTaskModel.ts` (export `extensionTypes`, or move it, if `eventWaitModel` needs it)

**Interfaces:**

```ts
export type EventWaitKind = 'receiveTask' | 'intermediateCatch' | 'boundary'

export interface EventWaitForm {
  eventKey: string
  correlations: { name: string; value: string }[]
  outputs: { source: string; target: string; transient: boolean }[]
}

/** Receive task with eventType, or intermediate/boundary event with no event definition and eventType. */
export function eventWaitKind(object?: ModdleElement): EventWaitKind | undefined
/** Intermediate/boundary event with no event definition: the shape the event mode can take. */
export function canBeEventWait(object?: ModdleElement): boolean
export function readEventWait(object?: ModdleElement): EventWaitForm
/**
 * Extension values for the form. Keeps every extension that is not eventType,
 * eventCorrelationParameter or eventOutParameter (execution listeners, other plugins).
 */
export function eventWaitExtensions(moddle: Moddle, current: ModdleElement[], form: EventWaitForm): ModdleElement[]
/** Removes the event extensions: used when switching a receive task back to manual. */
export function withoutEventExtensions(current: ModdleElement[]): ModdleElement[]
export function eventWaitProblems(form: EventWaitForm, event?: EventModelView): string[]   // mirrors the guard, rows 6–10
export function eventWaitWarnings(input: {
  form: EventWaitForm
  event?: EventModelView
  multiInstanceElementVariable?: string   // R-B5
  asyncLeave: boolean
  hasTimeout?: boolean                    // receive task only
}): string[]
```

Rules:
- **Problems** reuse the guard's Vietnamese messages, without the `Bước X:` prefix.
- **Warnings:**
  - A correlation field of the event that has no row: `Trường tương quan N của sự kiện không được dùng. Mọi sự kiện có cùng các trường còn lại đều khớp` (R-B4).
  - In multi-instance, a value that does not mention the element variable: `Trong bước lặp, giá trị tương quan nên dùng biến phần tử ${…}, nếu không mọi lượt lặp sẽ cùng nhận một sự kiện` (R-B5).
  - No timeout on a receive task (reuse `waitTaskWarnings`).
  - `asyncLeave` off.

- [ ] **Step 1: Specs first:**
  - The three kinds are detected; a timer intermediate event is not an event wait.
  - Read then write keeps an existing `flowable:ExecutionListener`.
  - Switching back to manual removes only the event extensions.
  - Each problem and warning has a case.
- [ ] **Step 2: Implement, run.** Expected: PASS.

---

### Task 8: Event wait panel and the manual/event switch (web)

**Files:**
- Create: `src/features/process-definition/components/EventWaitConfigPanel.vue`
- Test: `src/features/process-definition/components/EventWaitConfigPanel.spec.ts`
- Modify: `src/features/process-definition/components/ReceiveTaskConfigPanel.vue` (mode switch)
- Modify: `src/features/process-definition/views/ProcessDesignerView.vue`

**Panel** (`props: element, modeler, events: EventModelView[] (published only), readOnly?: boolean`), in three blocks as the spec says:
1. **Sự kiện:**
   - a select of published events (`name (key)`), followed by the event's description and its field list;
   - changing the event resets correlations to one row per correlation field, with value `${<field>}`, and clears outputs whose source no longer exists.
2. **Tương quan:**
   - one row per correlation field of the event: the field name (read-only) and an expression input;
   - a row can be removed, which triggers the R-B4 warning;
   - a removed row can be added back.
3. **Dữ liệu nhận:**
   - rows of `field select → variable input`, plus a "Chỉ dùng tạm" checkbox (`transient`);
   - "Thêm" and "Xóa" buttons.

Below the blocks:
- **Hạn chờ** (receive task only): reuse the timer-boundary button from `ReceiveTaskConfigPanel`. On an intermediate event, show the hint `Muốn có hạn chờ, đặt sự kiện sau cổng rẽ theo sự kiện cùng một sự kiện hẹn giờ`.
- **Rời bước bất đồng bộ** checkbox, on by default.
- Problems (`el-alert type="error"`) and warnings (`type="warning"`).

Writes go through `modeling.updateModdleProperties(element, object, { extensionElements })` with `eventWaitExtensions`, applied on `change` / `blur`, not on every keystroke.

**`ReceiveTaskConfigPanel`:**
- A radio "Chờ tiếp tục thủ công" / "Chờ sự kiện". "Chờ sự kiện" is hidden when `!eventsEnabled`.
- Switching to event writes an empty event form plus `asyncLeave: true`. Switching to manual removes the event extensions after `confirmAction` when there was a configured event.
- Event mode renders `EventWaitConfigPanel`.

**`ProcessDesignerView`:**
- Load the published events once, when `eventsEnabled`. Use `eventRegistryApi.events()` from `@/features/event-registry` and keep those with `publishedVersion`. A failed load shows one `ElMessage.warning` and an empty list.
- `isManualWait` stays. Add `isEventWait = computed(() => Boolean(eventWaitKind(selectedBusinessObject.value)))`.
- Render `EventWaitConfigPanel` for intermediate and boundary event waits. Receive tasks get it through `ReceiveTaskConfigPanel`.
- An event wait selected while `!eventsEnabled` gets `readOnly`, plus the warning `Tính năng sự kiện chưa bật, bước này sẽ bị từ chối khi phát hành`.
- Labels: `'bpmn:IntermediateCatchEvent'` with an event type shows "Chờ sự kiện". A boundary with an event type shows "Sự kiện gắn biên".

- [ ] **Step 1: Spec first** (`EventWaitConfigPanel.spec.ts`, modeler stubbed with `get('modeling' | 'moddle')`, typed `vi.fn`):
  - Picking an event creates one correlation row per correlation field, with `${field}`.
  - Removing a correlation row shows the R-B4 warning.
  - An output with an internal target (`tenantId`) shows the problem.
  - The written extensions keep an existing execution listener.
- [ ] **Step 2: Implement and wire.** Run `npm run type-check && npx vitest --run src/features/process-definition src/bpmn`. Expected: PASS.

---

### Task 9: Palette and replace menu (web)

**Files:**
- Modify: `src/bpmn/workflowPaletteModule.ts` + spec
- Modify: `src/bpmn/workflowReplaceMenuModule.ts`
- Modify: `src/bpmn/subProcessModel.ts` (`filterReplaceEntries`) + spec

- [ ] **Step 1: Palette.**
  - `create.event-wait` "Chờ sự kiện", icon `bpmn-icon-intermediate-event-none`, only when `eventsEnabled`.
  - It creates `bpmn:IntermediateCatchEvent` with `asyncLeave: true` and `extensionElements` holding an empty `flowable:EventType`.
  - Spec: present with the flag on and absent with it off. Mock `@/config/product`.
- [ ] **Step 2: Replace menu.**
  - Register a second provider with `getPopupMenuEntries` that **adds** entries when `eventsEnabled`:
    - for `bpmn:IntermediateCatchEvent` / `bpmn:IntermediateThrowEvent`: `replace-with-event-registry-catch` "Chờ sự kiện (Event Registry)";
    - for `bpmn:BoundaryEvent`: `replace-with-event-registry-boundary` "Sự kiện (Event Registry)". It keeps `cancelActivity` and `attachedToRef`.
  - The action uses `bpmnReplace.replaceElement(element, { type, eventDefinitionType: undefined })`, then sets the extensions and `asyncLeave` on the new element through `modeling`.
  - Check bpmn-js `PopupMenuProvider` signatures in `node_modules/bpmn-js/lib/features/popup-menu/ReplaceMenuProvider.js` before writing it.
- [ ] **Step 3: `filterReplaceEntries`.** Let `replace-with-event-registry-boundary` through for boundaries on embedded subprocesses, matching Task 3. Spec case.
- [ ] **Step 4: Run** the web checks. Expected: PASS.

---

## Slice 6 — Operations

### Task 10: Event waits in the instance's waits

**Files:**
- Modify: `…/process/application/dto/ProcessWaitView.java`
- Create: `…/process/application/dto/WaitCorrelationView.java`
- Create: `…/process/infrastructure/flowable/eventregistry/CorrelationProbe.java`
- Modify: `…/process/infrastructure/flowable/ReceiveTaskOperationServiceImpl.java`
- Test: `ReceiveTaskOperationServiceImplTest` (extend), `CorrelationProbeTest` (in-memory engine)

**Interfaces:**

```java
public record ProcessWaitView(
        String kind,                // MANUAL | EVENT
        String activityId,
        String activityName,
        String executionId,
        OffsetDateTime since,
        String elementType,         // receiveTask | intermediateCatchEvent | boundaryEvent
        String eventKey,            // null for MANUAL
        List<WaitCorrelationView> correlations,
        Boolean correlationChanged, // null when it cannot be checked
        OffsetDateTime dueDate) {   // earliest timer job of a timer boundary on the activity, else null
    public static final String MANUAL = "MANUAL";
    public static final String EVENT = "EVENT";
}

public record WaitCorrelationView(String name, String expression, String currentValue, boolean evaluated) { }
```

Keep a constructor for the old 5-argument shape if call sites in tests need it. Otherwise update the call sites.

`CorrelationProbe implements Command<CorrelationProbe.Result>`, built with `(executionId, activityId, subscriptionConfiguration)`:

```java
@Override
public Result execute(CommandContext context) {
    ExecutionEntity execution = CommandContextUtil.getExecutionEntityManager(context).findById(executionId);
    FlowElement element = ProcessDefinitionUtil.getBpmnModel(execution.getProcessDefinitionId())
            .getFlowElement(activityId);
    var expressions = CommandContextUtil.getProcessEngineConfiguration(context).getExpressionManager();
    var transformer = CommandContextUtil.getEventRegistryEngineConfiguration(context).getCorrelationValueTransformer();
    List<WaitCorrelationView> fields = new ArrayList<>();
    boolean all = true;
    for (ExtensionElement parameter : element.getExtensionElements()
            .getOrDefault(BpmnXMLConstants.ELEMENT_EVENT_CORRELATION_PARAMETER, List.of())) {
        String name = parameter.getAttributeValue(null, "name");
        String expression = parameter.getAttributeValue(null, "value");
        if (expression == null || !SIMPLE_PATH.matcher(expression.trim()).matches()) {
            fields.add(new WaitCorrelationView(name, expression, null, false));
            all = false;
            continue;
        }
        try {
            Object value = transformer.transformRawValue(expressions.createExpression(expression).getValue(execution));
            fields.add(new WaitCorrelationView(name, expression, value == null ? null : value.toString(), true));
        } catch (RuntimeException exception) {
            fields.add(new WaitCorrelationView(name, expression, null, false));
            all = false;
        }
    }
    Boolean changed = all
            ? !CorrelationUtil.getCorrelationKey(BpmnXMLConstants.ELEMENT_EVENT_CORRELATION_PARAMETER,
                    context, element, execution).equals(subscriptionConfiguration)
            : null;
    return new Result(fields, changed);
}
```

Before relying on them, check in `flsrc` that `CommandContextUtil.getExecutionEntityManager`, `ProcessDefinitionUtil.getBpmnModel` and `CorrelationUtil.getCorrelationKey(String, CommandContext, FlowElement, ExecutionEntity)` are public in 8.0.0.

`waits(processInstanceId)`, in addition to the manual waits:
1. List the instance's event subscriptions with `runtimeService.createEventSubscriptionQuery().processInstanceId(id).tenantId(tenantId).list()`.
2. Keep those whose activity's flow element has a `flowable:eventType` equal to the subscription's `eventType`. This drops message, signal and compensation subscriptions.
3. Run `managementService.executeCommand(new CorrelationProbe(...))` for each one.
4. Set `dueDate`:
   - receive task: the earliest `duedate` among timer jobs (`managementService.createTimerJobQuery().processInstanceId(id)`) whose `elementId` is a timer boundary attached to that activity;
   - intermediate and boundary event waits: `null`.
5. Sort by `since`.

- [ ] **Step 1: `CorrelationProbeTest`:**
  - Unchanged variable: `changed = false`, with the value shown.
  - `runtimeService.setVariable(orderId, "B")` after the wait started: `changed = true`, with the new value shown.
  - `${orderService.key(orderId)}`: `evaluated = false`, `changed = null`, and the bean is never called (use a mock bean in `beans`).
- [ ] **Step 2: Service test:**
  - A process with a manual wait and an event wait in parallel returns both, with kinds `MANUAL` and `EVENT`.
  - A message subscription is not listed.
  - `dueDate` comes from a `PT1H` timer boundary.
  - `trigger` on the event wait still fails with "không phải bước chờ thủ công".
- [ ] **Step 3: Run.** Expected: PASS.

---

### Task 11: Tenant-wide event waits and usages

**Files:**
- Create: `…/eventregistry/application/EventWaitQueryService.java`
- Create: `…/eventregistry/application/dto/EventWaitView.java` (`processInstanceId, processDefinitionKey, processDefinitionName, businessKey, activityId, activityName, executionId, since`)
- Create: `…/eventregistry/application/dto/EventUsageView.java` (`processDefinitionKey, version, activityIds`)
- Create: `…/eventregistry/infrastructure/EventWaitQueryServiceImpl.java`
- Create: `…/eventregistry/interfaces/rest/EventWaitController.java`
- Modify: `EventModelController.java` (`GET /{key}/usages`)
- Test: `EventWaitQueryServiceImplTest` (in-memory engine)

Endpoints:
- `GET /workflow/management/event-registry/waits?eventKey=&page=1&size=20`
  - Role `SERVICE, WF_PLATFORM_ADMIN, WF_ADMIN, WF_OPERATOR, WF_VIEWER`.
  - Returns `PageResult<EventWaitView>`, from `createEventSubscriptionQuery().tenantId(t).eventType(key)`, `orderByCreateDate().desc()`, `listPage`.
  - `size` is capped at 100. A blank or invalid `eventKey` gives `BAD_REQUEST`.
  - Instance names come from one `createProcessInstanceQuery().processInstanceIds(...)` call per page.
- `GET /workflow/management/event-registry/events/{key}/usages`
  - Role `READ`.
  - Returns `{ usages: EventUsageView[], waiting: long }`, from `EventReferenceRepository#usages` grouped by process and version, plus `gateway.waitingSubscriptions`.

- [ ] **Step 1: Test first:**
  - Two tenants, each with waits on the same key: only the caller's are listed.
  - Paging works.
  - Usages group the activity ids.
- [ ] **Step 2: Implement, run the engine and boot tests.** Expected: PASS, ArchUnit included (REST uses DTOs only).

---

### Task 12: Instance "Đang chờ" shows event waits (web)

**Files:**
- Modify: `src/features/process-instance/types.ts` (`ProcessWait`: `kind: 'MANUAL' | 'EVENT'`, plus the new fields)
- Modify: `src/features/process-instance/components/ProcessWaitsPanel.vue`
- Test: `src/features/process-instance/components/ProcessWaitsPanel.spec.ts` (new)

Display:
- **Event waits:**
  - a "Chờ sự kiện `<eventKey>`" badge, the element type label, the time since, and the due date when there is one;
  - a small table of correlation fields with name, expression and current value, or "không tính lại" when `!evaluated`;
  - when `correlationChanged === true`, a warning `Biến đã đổi sau khi bắt đầu chờ. Sự kiện cần mang giá trị cũ để khớp`;
  - no "Tiếp tục" button.
- Manual waits are unchanged.

- [ ] **Step 1: Spec:**
  - An event wait has no button and shows its values.
  - The changed warning appears only when `correlationChanged` is true.
  - A manual wait still has "Tiếp tục" when `canOperate`.
- [ ] **Step 2: Implement, run.** Expected: PASS.

---

### Task 13: "Đang chờ" tab and "Đang được dùng ở" (web)

**Files:**
- Modify: `src/features/event-registry/api.ts` (`waits(eventKey, page, size)`, `usages(key)`)
- Create: `src/features/event-registry/EventWaitsPanel.vue` + spec
- Modify: `src/features/event-registry/EventRegistryView.vue` (third tab "Đang chờ", `lazy`)
- Modify: `src/features/event-registry/EventModelPanel.vue` (usages block)
- Modify: `src/router/index.ts` (`requiredAnyRoles` for `event-registry` becomes `ROLES.anyUser`)
- Modify: `EventModelPanel.vue`, `RestChannelPanel.vue` (write buttons only for `canDesign`)

Decisions:
- The spec gives the "Đang chờ" tab to `WF_OPERATOR` as well. Reads are already open to `WF_OPERATOR` and `WF_VIEWER` on the backend.
- So the route becomes readable for every role, and the write buttons (create, save, publish, delete) are hidden unless the user can design. Use `canDesign` from `@/features/auth/authorization`, or its existing equivalent.
- The EIM matrix (Task 15) gives `WF_EVENT` to `WF_OPERATOR` and `WF_VIEWER`.

**`EventWaitsPanel`:**
- an event select (published events);
- a table with instance, process, step, executionId and time since;
- a row links to `{ name: 'process-instance-detail', params: { id } }` (check the route name in `src/router/index.ts`);
- `TablePagination`;
- the empty state `Không có công việc nào đang chờ sự kiện này`.

**Usages block** in `EventModelPanel`:
- "Đang được dùng ở": the list of `process vN · activity ids`, and "N công việc đang chờ";
- when the list is not empty and the draft is dirty, the warning `Sự kiện đang được quy trình dùng. Chỉ thêm trường mới; thay đổi khác cần mã sự kiện mới` (R-E4).

- [ ] **Step 1: Specs:**
  - The waits table pages.
  - A viewer sees no write buttons.
  - Usages render and warn on a dirty draft.
- [ ] **Step 2: Implement, run** `npm run type-check && npx vitest --run`, plus oxlint and eslint on the changed paths. Expected: PASS.

---

### Task 14: End-to-end check on the local stack

Requires `WORKFLOW_EVENT_REGISTRY_ENABLED=true`, `VITE_EVENTS_ENABLED=true`, event `paymentReceived` and channel `payWebhook` from slices 3–4.

- [ ] **Step 1:** In the designer:
  1. Add "Bước chờ" and switch it to "Chờ sự kiện".
  2. Pick `paymentReceived`. Expected: the correlation row `orderId = ${orderId}`.
  3. Map `amount → paidAmount`, then publish. Expected: success.
- [ ] **Step 2:** Remove the correlation row and publish. Expected: refused with `Bước … phải có ít nhất một trường tương quan`. Restore the row.
- [ ] **Step 3:** Start an instance with `orderId = SO-1`. Expected: "Đang chờ" shows the event wait with `orderId: SO-1` and no "Tiếp tục" button.
- [ ] **Step 4:** Change the `orderId` variable. Expected: the changed warning appears. Set it back.
- [ ] **Step 5:** Post the curl from "Kênh nhận" with `{"orderId":"SO-1","amount":10}`. Expected: `200 MATCHED`, and the instance moved on with `paidAmount = 10`.
- [ ] **Step 6:** Expected: "Sự kiện → paymentReceived" shows "Đang được dùng ở: <process> vN", and deleting it is refused.
- [ ] **Step 7:** Add an intermediate "Chờ sự kiện" and an interrupting boundary "Sự kiện (Event Registry)" on a user task, then publish and trigger both through the webhook. Expected: the intermediate continues, and the boundary cancels the user task.

---

### Task 15: Documentation and EIM

- [ ] **Step 1:** In `docs/superpowers/specs/2026-11-03-eim-configuration-checklist.md`, the role matrix gives `WF_EVENT` to `WF_OPERATOR` and `WF_VIEWER`. Explain why: the "Đang chờ" tab, with editing still limited to designers.
- [ ] **Step 2:** In the D1 spec:
  - Update «Lát triển khai → Trạng thái» for slices 5–6.
  - Write down the decisions taken here: the `CorrelationProbe` plain-path rule, the event-registry boundary on embedded subprocesses, the route opened to all read roles, and that intermediate waits get their timeout through an event-based gateway.
- [ ] **Step 3:** In the webhook guide, add "Bước chờ sẽ nhận sự kiện nào": which BPMN elements can wait, and that a value changed after the wait started will not match (R-B2).

---

### Task 16: Full verification

- [ ] **Step 1:** `mvn -o verify -Dcheckstyle.skip=true -Djacoco.haltOnFailure=false`, then `mvn -o -pl workflow-engine,workflow-boot,platform-migrations checkstyle:check@verify-java-conventions`. Expected:
  - BUILD SUCCESS, with SpotBugs 0;
  - no `Rule violated` for `workflow-engine` or `workflow-boot`;
  - the existing `service-registry` JaCoCo warning is reported, not fixed.
- [ ] **Step 2:** `npm run type-check && npx vitest --run`, plus oxlint and eslint on the changed paths. Expected: PASS.
- [ ] **Step 3:** Report the changed files. Do not commit.

---

## Open points to settle while executing

1. **Shape of an event wait inside multi-instance.** Flowable creates one subscription per inner execution. The guard does not require the element variable, and the designer only warns (R-B5). Confirm with the user if they want it to be an error instead.
2. **Intermediate "none" catch without `eventType`.** Flowable treats it as a plain wait state, triggered like a receive task. It is neither rejected nor offered today. Leave it as is in D1, but note it in the spec.
3. **Package dependency.** After Task 3, `process.infrastructure` depends on `eventregistry.application.port`, while `eventregistry.infrastructure` already depends on `process.infrastructure.flowable.eventregistry` and `process.application`. No ArchUnit rule forbids this today. If reviewers want no cycle, move `RestInboundChannelModel*` and `InboundDispatchContext` into `eventregistry.infrastructure` in a separate refactor.
