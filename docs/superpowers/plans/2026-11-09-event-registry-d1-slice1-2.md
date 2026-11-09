# Event Registry D1 — Slices 1–2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Slice 1 installs the Event Registry extension points and the schema that later slices need, and blocks any event-registry BPMN from being published until those slices exist. Slice 2 lets a designer put a manual wait step (plain `receiveTask`) on the diagram and lets an operator continue it from the instance screen.

**Spec:** `docs/superpowers/specs/2026-11-09-event-registry-d1-design.md` (research and risk ids R-xx: `docs/superpowers/specs/2026-11-09-receive-task-research.md`)

**Architecture:**
- **Engine:** everything goes through public Flowable 8.0.0 extension points. No Flowable class is patched.
  - `WorkflowBpmnEventRegistryEventConsumer` replaces the BPMN consumer through `setEventRegistryEventConsumer`. It catches the failure of one subscription so the others still run, and records each outcome in a thread-local `InboundDispatchContext`.
  - `WorkflowNonMatchingEventConsumer` marks the context when no subscription matched.
  - A `rest` inbound channel type is registered on `ChannelJsonConverter`. `RestInboundChannelModelProcessor` keeps the registered instances (with their pipeline) so a later webhook can hand events to `EventRegistry.eventReceived`.
- **Deployment:** `EventRegistryDeploymentGuard` runs inside `ProcessDeployer.deployChecked` and rejects every event-registry element for now.
- **Manual wait:**
  - `ReceiveTaskOperationService` lists the manual waits of an instance and triggers one after a whitelist check. The check accepts only a `receiveTask` with no `workflow:agentTask` and no `flowable:eventType`.
  - The web designer gets a "Bước chờ" palette entry, `flowable:asyncLeave`, and a panel with a "Thêm hạn chờ" shortcut. The instance detail gets a "Đang chờ" tab.

**Tech Stack:** Java 25, Spring Boot 4.1, Flowable 8.0.0 (event registry bundled), JUnit 5, AssertJ, Mockito, H2 (test), PostgreSQL + Flyway, Vue 3, Vitest, Element Plus, bpmn-js.

## Execution notes (2026-11-09)

Slices 1–2 are implemented and pass automated checks. They are not committed, at the user's request. The manual UI checks (Task 14 Step 7, Task 15 Step 8) have not been done.

Deviations from the steps below:

- **Task 5:** added a lazy `commandExecutor` lookup to the consumer. See the note in Task 5.
- **Task 7:**
  - Added an `EventRegistryChangeDetectionExecutor` bean using the `(long, long)` constructor, because Flowable's default executor fails when there are several `TaskScheduler` beans (Agent worker plus completion).
  - Added `workflow-boot/src/test/.../eventregistry/EventRegistryWiringIntegrationTest.java`, which keeps the worker enabled, instead of the manual start-up check.
- **Task 8:** `PlatformMigrationIntegrationTest` now expects 40 migrations and 9 forced-RLS tables.
- **Task 13:** `flowableModdle.ts` also declares `EventType`, `EventCorrelationParameter` and `EventOutParameter`. Without them bpmn-moddle drops those elements on import.
- **Task 16:** `mvn verify` is blocked by issues that already exist in untouched modules (auth-engine Checkstyle, service-registry JaCoCo). The run used `-Dcheckstyle.skip=true -Djacoco.haltOnFailure=false`, then a separate Checkstyle run on the changed modules (0 violations).

## Global Constraints

- Flowable stays `8.0.0`. Do not add Kafka, RabbitMQ or JMS dependencies in these slices.
- Do not edit or reformat lines a teammate already changed in the working tree (`application.yml`, `.env.example`, `SubProcessProperties.java`, `product.ts` have uncommitted edits). Only append the lines this plan names.
- XML names come from `BpmnXMLConstants` 8.0.0: `flowable:eventType`, `flowable:eventCorrelationParameter`, `flowable:eventOutParameter`, `flowable:asyncLeave`, `flowable:triggerEventType`, `flowable:type="send-event"`.
- Treat `http://flowable.org/bpmn` and `http://activiti.org/bpmn` as the same namespace, as Flowable does.
- The guard throws `BusinessException(ErrorCode.BAD_REQUEST, …)` like the other deploy guards. Messages are Vietnamese and name the element id.
- The guard rejects every event-registry element in slices 1–2, whatever `workflow.event-registry.enabled` says. Slice 5 relaxes it.
- The manual trigger uses `ErrorCode.CONFLICT` for "not a manual wait", "not waiting" and "ambiguous execution", and `ErrorCode.NOT_FOUND` for a missing instance. These match the existing terminate operation.
- Never run event dispatch inside an outer Spring transaction. Each `runtimeService.trigger` must be its own Flowable command, otherwise one failure marks the whole transaction rollback-only (R-D2).
- Audit records variable **names** only, never values.
- `resolveStartFrontierKinds` in `utils/bpmn.ts` is only used by a test. Do not change it in these slices.
- Web: the palette entry is always visible. Manual waits do not depend on any event-registry flag.

Java commands run from `workflow-agent-service`. Web commands run from `workflow-agent-web`.

---

## Slice 1 — Engine foundation

### Task 1: Check the database preconditions

This task produces facts, not code. Run it against every shared environment (dev, staging) before Task 8 is merged.

- [ ] **Step 1: Is RLS active on Flowable tables?**

```sql
SELECT relname, relrowsecurity, relforcerowsecurity
FROM pg_class WHERE relname LIKE 'act_ru_%' ORDER BY relname;
```

- [ ] **Step 2: Do tenant id and tenant code differ?**

```sql
SELECT tenant_id, tenant_code FROM workflow_tenant;
SELECT DISTINCT tenant_id_ FROM act_re_procdef;
```

- [ ] **Step 3: Do the event registry tables exist?**

```sql
SELECT table_name FROM information_schema.tables
WHERE table_name IN ('flw_event_deployment','flw_event_resource','flw_event_definition','flw_channel_definition');
```

- [ ] **Step 4: Record the results**

Add a section `## Kết quả kiểm tra tiền đề` to `docs/superpowers/specs/2026-11-09-event-registry-d1-design.md` with the three outputs per environment.

- If `relrowsecurity` is true on `act_ru_*` **and** tenant id ≠ tenant code, stop and raise it. Running queries are already broken in that case.
- If the `flw_*` tables are missing, stop. The engine cannot start the event registry with `database-schema-update: false`.

```bash
git add docs/superpowers/specs/2026-11-09-event-registry-d1-design.md
git commit -m "docs: record event registry database preconditions"
```

---

### Task 2: Properties and engine settings

**Files:**
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/config/EventRegistryProperties.java`
- Modify: `workflow-boot/src/main/resources/application.yml` (append only)
- Modify: `.env.example` (append only)

**Interfaces:**
- Produces: `EventRegistryProperties#isEnabled()`, bound to `workflow.event-registry.enabled`, default `false`.

- [ ] **Step 1: Confirm the Spring event registry module is on the classpath**

Run: `mvn -q -pl workflow-engine dependency:tree -Dincludes=org.flowable:flowable-event-registry-spring`

Expected: one line listing `flowable-event-registry-spring:jar:8.0.0`. If it is missing, add it to `workflow-engine/pom.xml` without a version (the Flowable BOM manages it), then rerun.

- [ ] **Step 2: Create the properties class**

```java
package io.github.illuseahashmap.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Event registry features. Manual receive tasks never depend on these settings. */
@ConfigurationProperties("workflow.event-registry")
public class EventRegistryProperties {

    /** Whether event models, channels and event waits may be published. */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
```

`@ConfigurationPropertiesScan` in `WorkflowAgentServiceApplication` already covers `io.github.illuseahashmap.workflow.config`. Check that this is still true before relying on it.

- [ ] **Step 3: Append the settings to `application.yml`**

Under the existing top-level `flowable:` key, append:

```yaml
  eventregistry:
    enable-change-detection: true
    change-detection-delay: 10s
```

Under the existing top-level `workflow:` key, append:

```yaml
  event-registry:
    enabled: ${WORKFLOW_EVENT_REGISTRY_ENABLED:false}
```

- [ ] **Step 4: Append to `.env.example`**

```bash
# Event registry (event waits, channels). Manual wait steps work without it.
WORKFLOW_EVENT_REGISTRY_ENABLED=false
```

- [ ] **Step 5: Compile**

Run: `mvn -q -pl workflow-engine,workflow-boot -am compile`

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add workflow-engine/src/main/java/io/github/illuseahashmap/workflow/config/EventRegistryProperties.java \
        workflow-boot/src/main/resources/application.yml .env.example
git commit -m "feat: add event registry settings"
```

---

### Task 3: Pin the Flowable 8.0.0 behaviour we rely on

**Files:**
- Create: `workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/EventRegistryEngineFactsTest.java`

No platform code is involved. If Flowable changes, these tests fail first.

- [ ] **Step 1: Write the facts test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flowable.common.engine.impl.interceptor.EngineConfigurationConstants;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.flowable.eventregistry.api.runtime.EventPayloadInstance;
import org.flowable.eventregistry.impl.EventRegistryEngineConfiguration;
import org.flowable.eventregistry.impl.event.FlowableEventRegistryEvent;
import org.flowable.eventregistry.impl.runtime.EventInstanceImpl;
import org.flowable.eventregistry.impl.runtime.EventPayloadInstanceImpl;
import org.flowable.eventregistry.model.EventModel;
import org.flowable.eventsubscription.api.EventSubscription;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Flowable 8.0.0 event registry facts behind spec 2026-11-09-event-registry-d1-design. */
class EventRegistryEngineFactsTest {

    static final String PAYMENT_EVENT = """
            {"key":"paymentReceived","name":"Payment received","payload":[
              {"name":"orderId","type":"string","correlationParameter":true},
              {"name":"amount","type":"double"}]}""";

    private static ProcessEngine engine;
    private static EventRegistry eventRegistry;
    private static EventRepositoryService eventRepository;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:event-registry-facts;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
        var events = (EventRegistryEngineConfiguration) configuration.getEngineConfigurations()
                .get(EngineConfigurationConstants.KEY_EVENT_REGISTRY_CONFIG);
        eventRegistry = events.getEventRegistry();
        eventRepository = events.getEventRepositoryService();
        eventRepository.createDeployment().addEventDefinition("payment.event", PAYMENT_EVENT).deploy();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    static String process(String key, String waitExtensions) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s"/>
                    <receiveTask id="wait">
                      <extensionElements>%s</extensionElements>
                    </receiveTask>
                    <userTask id="after"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="after"/>
                  </process>
                </definitions>""".formatted(key, waitExtensions);
    }

    static void deploy(String key, String waitExtensions) {
        engine.getRepositoryService().createDeployment()
                .addString(key + ".bpmn20.xml", process(key, waitExtensions)).deploy();
    }

    static void send(String orderId, double amount) {
        EventModel model = eventRepository.getEventModelByKey("paymentReceived");
        List<EventPayloadInstance> payload = List.of(
                new EventPayloadInstanceImpl(model.getPayload("orderId"), orderId),
                new EventPayloadInstanceImpl(model.getPayload("amount"), amount));
        eventRegistry.sendEventToConsumers(new FlowableEventRegistryEvent(
                new EventInstanceImpl("paymentReceived", payload)));
    }

    static boolean waiting(ProcessInstance instance) {
        return engine.getRuntimeService().createExecutionQuery()
                .processInstanceId(instance.getId()).activityId("wait").count() > 0;
    }

    @Test
    void eventTypeTurnsReceiveTaskIntoSubscriptionWithHashedCorrelation() {
        deploy("factsSubscription", """
                <flowable:eventType>paymentReceived</flowable:eventType>
                <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>""");
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey("factsSubscription", Map.of("orderId", "SO-1"));

        EventSubscription subscription = engine.getRuntimeService().createEventSubscriptionQuery()
                .processInstanceId(instance.getId()).singleResult();
        assertThat(subscription.getEventType()).isEqualTo("paymentReceived");
        assertThat(subscription.getActivityId()).isEqualTo("wait");
        assertThat(subscription.getConfiguration()).isEqualTo(eventRegistry.generateKey(Map.of("orderId", "SO-1")));
    }

    @Test
    void matchingEventTriggersAndMapsPayloadOtherInstancesStay() {
        deploy("factsMatch", """
                <flowable:eventType>paymentReceived</flowable:eventType>
                <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>
                <flowable:eventOutParameter source="amount" target="paidAmount"/>""");
        ProcessInstance a = engine.getRuntimeService().startProcessInstanceByKey("factsMatch", Map.of("orderId", "A"));
        ProcessInstance b = engine.getRuntimeService().startProcessInstanceByKey("factsMatch", Map.of("orderId", "B"));

        send("A", 120.5);

        assertThat(waiting(a)).isFalse();
        assertThat(engine.getRuntimeService().getVariable(a.getId(), "paidAmount")).isEqualTo(120.5);
        assertThat(waiting(b)).isTrue();
    }

    @Test
    void waitWithoutCorrelationIsTriggeredByAnyEventOfThatKey() {
        // R-B1: the reason the guard will demand at least one correlation parameter.
        deploy("factsNoCorrelation", "<flowable:eventType>paymentReceived</flowable:eventType>");
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("factsNoCorrelation");

        send("whatever", 1.0);

        assertThat(waiting(instance)).isFalse();
    }

    @Test
    void redeployingTheSameEventJsonBumpsTheVersion() {
        // R-E1: the reason event models are deployed only when their content changes.
        long before = eventRepository.createEventDefinitionQuery().eventDefinitionKey("paymentReceived").count();
        eventRepository.createDeployment().addEventDefinition("payment.event", PAYMENT_EVENT).deploy();

        assertThat(eventRepository.createEventDefinitionQuery().eventDefinitionKey("paymentReceived").count())
                .isEqualTo(before + 1);
    }
}
```

- [ ] **Step 2: Run it**

Run: `mvn -q -pl workflow-engine test -Dtest=EventRegistryEngineFactsTest`

Expected: 4 tests PASS.

- If `getPayload(String)` does not exist on `EventModel`, use `model.getPayload().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow()`.
- If a fact itself is false, stop and update the research spec instead of the test.

- [ ] **Step 3: Commit**

```bash
git add workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/EventRegistryEngineFactsTest.java
git commit -m "test: pin Flowable 8 event registry behaviour"
```

---

### Task 4: Dispatch context

**Files:**
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/InboundDispatchContext.java`
- Test: `workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/InboundDispatchContextTest.java`

**Interfaces:**
- Produces:
  - `InboundDispatchContext.open()` returns an `AutoCloseable` context bound to the current thread.
  - `static Optional<InboundDispatchContext> current()`.
  - `recordSuccess(String processInstanceId, String activityId)`, `recordFailure(String processInstanceId, String activityId, String errorCode)`, `markNoMatch()`.
  - `List<Outcome> outcomes()`, `Result result()`, where `Result ∈ {MATCHED, PARTIAL, NO_MATCH, FAILED}`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InboundDispatchContextTest {

    @Test
    void resultFollowsOutcomes() {
        try (var context = InboundDispatchContext.open()) {
            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.NO_MATCH);
            context.recordSuccess("p1", "wait");
            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.MATCHED);
            context.recordFailure("p2", "wait", "CONCURRENT_UPDATE");
            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.PARTIAL);
        }
        try (var context = InboundDispatchContext.open()) {
            context.recordFailure("p2", "wait", "EVENT_PROCESSING_FAILED");
            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.FAILED);
        }
    }

    @Test
    void contextIsThreadBoundAndClosed() {
        assertThat(InboundDispatchContext.current()).isEmpty();
        try (var context = InboundDispatchContext.open()) {
            assertThat(InboundDispatchContext.current()).containsSame(context);
        }
        assertThat(InboundDispatchContext.current()).isEmpty();
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `mvn -q -pl workflow-engine test -Dtest=InboundDispatchContextTest`

Expected: compilation failure, `InboundDispatchContext` missing.

- [ ] **Step 3: Implement**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Collects what happened to one inbound event on the current thread: which waiting executions
 * were triggered, which failed, or that nothing matched. Opened by whoever hands the event to
 * the engine (the REST channel in slice 4); filled by the consumers below.
 */
public final class InboundDispatchContext implements AutoCloseable {

    public enum Result { MATCHED, PARTIAL, NO_MATCH, FAILED }

    public record Outcome(String processInstanceId, String activityId, String errorCode) {
        public boolean succeeded() {
            return errorCode == null;
        }
    }

    private static final ThreadLocal<InboundDispatchContext> CURRENT = new ThreadLocal<>();

    private final List<Outcome> outcomes = new ArrayList<>();
    private boolean noMatch;

    private InboundDispatchContext() {
    }

    public static InboundDispatchContext open() {
        InboundDispatchContext context = new InboundDispatchContext();
        CURRENT.set(context);
        return context;
    }

    public static Optional<InboundDispatchContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public void recordSuccess(String processInstanceId, String activityId) {
        outcomes.add(new Outcome(processInstanceId, activityId, null));
    }

    public void recordFailure(String processInstanceId, String activityId, String errorCode) {
        outcomes.add(new Outcome(processInstanceId, activityId, errorCode));
    }

    public void markNoMatch() {
        noMatch = true;
    }

    public List<Outcome> outcomes() {
        return List.copyOf(outcomes);
    }

    public Result result() {
        long succeeded = outcomes.stream().filter(Outcome::succeeded).count();
        long failed = outcomes.size() - succeeded;
        if (succeeded > 0) {
            return failed > 0 ? Result.PARTIAL : Result.MATCHED;
        }
        return failed > 0 ? Result.FAILED : Result.NO_MATCH;
    }

    boolean noMatchReported() {
        return noMatch;
    }

    @Override
    public void close() {
        CURRENT.remove();
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -q -pl workflow-engine test -Dtest=InboundDispatchContextTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/InboundDispatchContext.java \
        workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/InboundDispatchContextTest.java
git commit -m "feat: add inbound event dispatch context"
```

---

### Task 5: Consumers that isolate failures and report no-match

**Files:**
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/WorkflowBpmnEventRegistryEventConsumer.java`
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/WorkflowNonMatchingEventConsumer.java`
- Test: `workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/WorkflowEventConsumersTest.java`

**Interfaces:**
- Consumes: `InboundDispatchContext` (Task 4).
- Produces:
  - `new WorkflowBpmnEventRegistryEventConsumer(ProcessEngineConfigurationImpl)`, to pass to `setEventRegistryEventConsumer`.
  - `new WorkflowNonMatchingEventConsumer()`, to pass to `EventRegistryEngineConfiguration#setNonMatchingEventConsumer`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flowable.common.engine.impl.interceptor.EngineConfigurationConstants;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.flowable.eventregistry.impl.EventRegistryEngineConfiguration;
import org.flowable.eventregistry.impl.event.FlowableEventRegistryEvent;
import org.flowable.eventregistry.impl.runtime.EventInstanceImpl;
import org.flowable.eventregistry.impl.runtime.EventPayloadInstanceImpl;
import org.flowable.eventregistry.model.EventModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorkflowEventConsumersTest {

    /** Fails the step after the wait when the instance has fail=true. */
    public static class FailingDelegate implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            if (Boolean.TRUE.equals(execution.getVariable("fail"))) {
                throw new IllegalStateException("downstream failure");
            }
        }
    }

    private static ProcessEngine engine;
    private static EventRegistry eventRegistry;
    private static EventRepositoryService eventRepository;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:event-consumers;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        configuration.setEventRegistryEventConsumer(new WorkflowBpmnEventRegistryEventConsumer(configuration));
        engine = configuration.buildProcessEngine();
        var events = (EventRegistryEngineConfiguration) configuration.getEngineConfigurations()
                .get(EngineConfigurationConstants.KEY_EVENT_REGISTRY_CONFIG);
        events.setNonMatchingEventConsumer(new WorkflowNonMatchingEventConsumer());
        eventRegistry = events.getEventRegistry();
        eventRepository = events.getEventRepositoryService();
        eventRepository.createDeployment()
                .addEventDefinition("payment.event", EventRegistryEngineFactsTest.PAYMENT_EVENT).deploy();
        engine.getRepositoryService().createDeployment().addString("consumers.bpmn20.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="consumers" isExecutable="true">
                    <startEvent id="s"/>
                    <receiveTask id="wait">
                      <extensionElements>
                        <flowable:eventType>paymentReceived</flowable:eventType>
                        <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>
                      </extensionElements>
                    </receiveTask>
                    <serviceTask id="next" flowable:class="%s"/>
                    <userTask id="after"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="next"/>
                    <sequenceFlow id="f3" sourceRef="next" targetRef="after"/>
                  </process>
                </definitions>""".formatted(FailingDelegate.class.getName())).deploy();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static void send(String orderId) {
        EventModel model = eventRepository.getEventModelByKey("paymentReceived");
        eventRegistry.sendEventToConsumers(new FlowableEventRegistryEvent(new EventInstanceImpl("paymentReceived",
                List.of(new EventPayloadInstanceImpl(model.getPayload("orderId"), orderId),
                        new EventPayloadInstanceImpl(model.getPayload("amount"), 1.0)))));
    }

    private static boolean waiting(ProcessInstance instance) {
        return engine.getRuntimeService().createExecutionQuery()
                .processInstanceId(instance.getId()).activityId("wait").count() > 0;
    }

    @Test
    void oneFailingSubscriptionDoesNotStopTheOthers() {
        ProcessInstance failing = engine.getRuntimeService()
                .startProcessInstanceByKey("consumers", Map.of("orderId", "SAME", "fail", true));
        ProcessInstance healthy = engine.getRuntimeService()
                .startProcessInstanceByKey("consumers", Map.of("orderId", "SAME", "fail", false));

        try (var context = InboundDispatchContext.open()) {
            send("SAME");

            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.PARTIAL);
            assertThat(context.outcomes()).extracting(InboundDispatchContext.Outcome::processInstanceId)
                    .containsExactlyInAnyOrder(failing.getId(), healthy.getId());
            assertThat(context.outcomes()).filteredOn(o -> !o.succeeded())
                    .singleElement().satisfies(o -> {
                        assertThat(o.processInstanceId()).isEqualTo(failing.getId());
                        assertThat(o.errorCode()).isEqualTo("EVENT_PROCESSING_FAILED");
                    });
        }
        assertThat(waiting(failing)).isTrue();   // rolled back to the wait
        assertThat(waiting(healthy)).isFalse();  // moved on
    }

    @Test
    void noMatchingSubscriptionIsReported() {
        try (var context = InboundDispatchContext.open()) {
            send("NOBODY-WAITS");

            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.NO_MATCH);
            assertThat(context.noMatchReported()).isTrue();
        }
    }

    @Test
    void worksWithoutAnOpenContext() {
        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey("consumers", Map.of("orderId", "NO-CONTEXT", "fail", false));

        send("NO-CONTEXT");

        assertThat(waiting(instance)).isFalse();
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `mvn -q -pl workflow-engine test -Dtest=WorkflowEventConsumersTest`

Expected: compilation failure, consumers missing.

- [ ] **Step 3: Implement the BPMN consumer**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import java.util.Collection;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.eventregistry.BpmnEventRegistryEventConsumer;
import org.flowable.eventregistry.api.EventConsumerInfo;
import org.flowable.eventregistry.api.runtime.EventInstance;
import org.flowable.eventregistry.impl.consumer.CorrelationKey;
import org.flowable.eventsubscription.api.EventSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flowable's BPMN consumer with one change: a failing subscription no longer stops the loop over
 * the remaining ones (R-D2). Matching and triggering are inherited unchanged. Each trigger is its
 * own Flowable command, so a failure rolls back only that execution.
 */
public class WorkflowBpmnEventRegistryEventConsumer extends BpmnEventRegistryEventConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowBpmnEventRegistryEventConsumer.class);

    public WorkflowBpmnEventRegistryEventConsumer(ProcessEngineConfigurationImpl processEngineConfiguration) {
        super(processEngineConfiguration);
    }

    @Override
    protected void handleEventSubscription(RuntimeService runtimeService, EventSubscription eventSubscription,
            EventInstance eventInstance, Collection<CorrelationKey> correlationKeys,
            EventConsumerInfo eventConsumerInfo) {
        try {
            super.handleEventSubscription(runtimeService, eventSubscription, eventInstance, correlationKeys,
                    eventConsumerInfo);
            InboundDispatchContext.current().ifPresent(context -> context.recordSuccess(
                    eventSubscription.getProcessInstanceId(), eventSubscription.getActivityId()));
        } catch (RuntimeException exception) {
            String code = exception instanceof FlowableOptimisticLockingException
                    ? "CONCURRENT_UPDATE" : "EVENT_PROCESSING_FAILED";
            // No payload or correlation values in the log.
            LOGGER.warn("Event {} failed for subscription {} (process instance {}, activity {}): {}",
                    eventInstance.getEventKey(), eventSubscription.getId(),
                    eventSubscription.getProcessInstanceId(), eventSubscription.getActivityId(), code, exception);
            InboundDispatchContext.current().ifPresent(context -> context.recordFailure(
                    eventSubscription.getProcessInstanceId(), eventSubscription.getActivityId(), code));
        }
    }
}
```

**Found while executing (2026-11-09):** `BaseEventRegistryEventConsumer` reads `commandExecutor` in its constructor. The consumer is created before the engine is built (in the test and in the Spring configurer), so that field is `null` and the first event fails with an NPE. Add this override to the class above. It is already in the implemented code.

```java
    @Override
    protected EventRegistryProcessingInfo eventReceived(EventInstance eventInstance) {
        if (commandExecutor == null) {
            commandExecutor = processEngineConfiguration.getCommandExecutor();
        }
        return super.eventReceived(eventInstance);
    }
```

- [ ] **Step 4: Implement the no-match consumer**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import org.flowable.eventregistry.api.EventRegistryNonMatchingEventConsumer;
import org.flowable.eventregistry.api.EventRegistryProcessingInfo;
import org.flowable.eventregistry.api.runtime.EventInstance;
import org.flowable.eventregistry.api.EventRegistryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Called by Flowable when no consumer handled an event. Marks the dispatch context (R-A1). */
public class WorkflowNonMatchingEventConsumer implements EventRegistryNonMatchingEventConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowNonMatchingEventConsumer.class);

    @Override
    public void handleNonMatchingEvent(EventRegistryEvent event, EventRegistryProcessingInfo processingInfo) {
        String key = event.getEventObject() instanceof EventInstance instance ? instance.getEventKey() : event.getType();
        LOGGER.info("No waiting instance matched event {}", key);
        InboundDispatchContext.current().ifPresent(InboundDispatchContext::markNoMatch);
    }
}
```

If `EventRegistryEvent` lives in another package in 8.0.0, fix the import from `flowable-event-registry-api`. Do not change the logic.

- [ ] **Step 5: Run the test**

Run: `mvn -q -pl workflow-engine test -Dtest=WorkflowEventConsumersTest`

Expected: 3 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/Workflow*.java \
        workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/WorkflowEventConsumersTest.java
git commit -m "feat: isolate failing event subscriptions and report unmatched events"
```

---

### Task 6: The `rest` inbound channel type

**Files:**
- Create: `…/eventregistry/RestInboundChannelModel.java`
- Create: `…/eventregistry/RestInboundChannelModelProcessor.java`
- Create: `…/eventregistry/WorkflowEventRegistryCustomizer.java`
- Test: `…/eventregistry/RestInboundChannelTest.java`

(`…` = `workflow-engine/src/{main,test}/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable`)

**Interfaces:**
- Produces:
  - `RestInboundChannelModel extends InboundChannelModel` with `List<String> getAllowedEventKeys()` and `Security getSecurity()`. `Security` has `List<String> getUserIds()` and `List<String> getGroupIds()`.
  - `RestInboundChannelModelProcessor#find(String tenantId, String channelKey)` returns `Optional<RestInboundChannelModel>` with the pipeline already attached.
  - `WorkflowEventRegistryCustomizer.customize(EventRegistryEngineConfiguration, RestInboundChannelModelProcessor)`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.eventregistry.impl.EventRegistryEngineConfiguration;
import org.flowable.eventregistry.impl.cfg.StandaloneInMemEventRegistryEngineConfiguration;
import org.flowable.eventregistry.impl.configurator.EventRegistryEngineConfigurator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RestInboundChannelTest {

    private static final RestInboundChannelModelProcessor CHANNELS = new RestInboundChannelModelProcessor();
    private static ProcessEngine engine;
    private static EventRegistryEngineConfiguration events;

    static String channel(String key, String security) {
        return """
                {"key":"%s","category":"channel","name":"Webhook","channelType":"inbound","type":"rest",
                 "deserializerType":"json","channelEventKeyDetection":{"fixedValue":"paymentReceived"},
                 "allowedEventKeys":["paymentReceived"],"security":%s}""".formatted(key, security);
    }

    @BeforeAll
    static void start() {
        events = new StandaloneInMemEventRegistryEngineConfiguration();
        WorkflowEventRegistryCustomizer.customize(events, CHANNELS);
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:rest-channel;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        configuration.setEventRegistryConfigurator(new EventRegistryEngineConfigurator().setEventEngineConfiguration(events));
        configuration.setEventRegistryEventConsumer(new WorkflowBpmnEventRegistryEventConsumer(configuration));
        engine = configuration.buildProcessEngine();
        events.getEventRepositoryService().createDeployment()
                .addEventDefinition("payment.event", EventRegistryEngineFactsTest.PAYMENT_EVENT).deploy();
        engine.getRepositoryService().createDeployment().addString("rest.bpmn20.xml",
                EventRegistryEngineFactsTest.process("restWait", """
                        <flowable:eventType>paymentReceived</flowable:eventType>
                        <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>
                        <flowable:eventOutParameter source="amount" target="paidAmount"/>""")).deploy();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @Test
    void restChannelJsonIsParsedRegisteredAndDeliversThroughThePipeline() {
        events.getEventRepositoryService().createDeployment()
                .addChannelDefinition("pay.channel", channel("payWebhook", "{\"userIds\":[\"erp-client\"],\"groupIds\":[]}"))
                .deploy();
        var instance = engine.getRuntimeService().startProcessInstanceByKey("restWait", Map.of("orderId", "R-1"));

        RestInboundChannelModel model = CHANNELS.find(null, "payWebhook").orElseThrow();
        assertThat(model.getAllowedEventKeys()).containsExactly("paymentReceived");
        assertThat(model.getSecurity().getUserIds()).containsExactly("erp-client");
        assertThat(model.getInboundEventProcessingPipeline()).isNotNull();

        try (var context = InboundDispatchContext.open()) {
            events.getEventRegistry().eventReceived(model, "{\"orderId\":\"R-1\",\"amount\":42.0}");
            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.MATCHED);
        }
        assertThat(engine.getRuntimeService().getVariable(instance.getId(), "paidAmount")).isEqualTo(42.0);
    }

    @Test
    void channelWithoutAnyAllowedPrincipalIsRejectedAtDeploy() {
        assertThatThrownBy(() -> events.getEventRepositoryService().createDeployment()
                .addChannelDefinition("open.channel", channel("openWebhook", "{\"userIds\":[],\"groupIds\":[]}"))
                .deploy())
                .hasMessageContaining("openWebhook");
        assertThat(CHANNELS.find(null, "openWebhook")).isEmpty();
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `mvn -q -pl workflow-engine test -Dtest=RestInboundChannelTest`

Expected: compilation failure.

- [ ] **Step 3: Implement the model**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import java.util.ArrayList;
import java.util.List;
import org.flowable.eventregistry.model.InboundChannelModel;

/**
 * Inbound channel reached over HTTP, like Flowable Enterprise's REST channel. It has no listener:
 * the platform's webhook endpoint hands each request to EventRegistry#eventReceived.
 */
public class RestInboundChannelModel extends InboundChannelModel {

    private List<String> allowedEventKeys = new ArrayList<>();
    private Security security = new Security();

    public RestInboundChannelModel() {
        setType("rest");
        setChannelType("inbound");
    }

    public List<String> getAllowedEventKeys() {
        return allowedEventKeys;
    }

    public void setAllowedEventKeys(List<String> allowedEventKeys) {
        this.allowedEventKeys = allowedEventKeys == null ? new ArrayList<>() : allowedEventKeys;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security == null ? new Security() : security;
    }

    /** Who may post to the channel: principal ids and role codes, as Enterprise's userIds / groupIds. */
    public static class Security {
        private List<String> userIds = new ArrayList<>();
        private List<String> groupIds = new ArrayList<>();

        public List<String> getUserIds() {
            return userIds;
        }

        public void setUserIds(List<String> userIds) {
            this.userIds = userIds == null ? new ArrayList<>() : userIds;
        }

        public List<String> getGroupIds() {
            return groupIds;
        }

        public void setGroupIds(List<String> groupIds) {
            this.groupIds = groupIds == null ? new ArrayList<>() : groupIds;
        }

        public boolean isEmpty() {
            return userIds.isEmpty() && groupIds.isEmpty();
        }
    }
}
```

- [ ] **Step 4: Implement the processor**

Flowable calls every processor whose `canProcess` is true with the **same** model instance. The core `InboundChannelModelProcessor` attaches the JSON pipeline to that instance. This processor validates the model and keeps a reference to it.

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.eventregistry.api.ChannelModelProcessor;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.flowable.eventregistry.model.ChannelModel;

/** Validates and remembers deployed REST channels, per tenant and key. Opens no listener. */
public class RestInboundChannelModelProcessor implements ChannelModelProcessor {

    private static final String NO_TENANT = "";
    private final Map<String, RestInboundChannelModel> channels = new ConcurrentHashMap<>();

    @Override
    public boolean canProcess(ChannelModel channelModel) {
        return channelModel instanceof RestInboundChannelModel;
    }

    @Override
    public boolean canProcessIfChannelModelAlreadyRegistered(ChannelModel channelModel) {
        return canProcess(channelModel);
    }

    @Override
    public void registerChannelModel(ChannelModel channelModel, String tenantId, EventRegistry eventRegistry,
            EventRepositoryService eventRepositoryService, boolean fallbackToDefaultTenant) {
        RestInboundChannelModel model = (RestInboundChannelModel) channelModel;
        if (model.getSecurity().isEmpty()) {
            throw new FlowableIllegalArgumentException(
                    "REST channel " + model.getKey() + " must allow at least one user or group");
        }
        if (!"json".equals(model.getDeserializerType())) {
            throw new FlowableIllegalArgumentException("REST channel " + model.getKey() + " must use deserializerType json");
        }
        channels.put(id(tenantId, model.getKey()), model);
    }

    @Override
    public void unregisterChannelModel(ChannelModel channelModel, String tenantId,
            EventRepositoryService eventRepositoryService) {
        // Only drop the exact instance being unregistered: a newer version may already be in place.
        channels.remove(id(tenantId, channelModel.getKey()), channelModel);
    }

    public Optional<RestInboundChannelModel> find(String tenantId, String channelKey) {
        return Optional.ofNullable(channels.get(id(tenantId, channelKey)))
                .filter(model -> model.getInboundEventProcessingPipeline() != null);
    }

    private static String id(String tenantId, String key) {
        return (tenantId == null ? NO_TENANT : tenantId) + "\u0000" + key;
    }
}
```

- [ ] **Step 5: Implement the customizer**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import org.flowable.eventregistry.impl.EventRegistryEngineConfiguration;

/** Every change the platform makes to the event registry engine, applied before it is built. */
public final class WorkflowEventRegistryCustomizer {

    private WorkflowEventRegistryCustomizer() {
    }

    public static void customize(EventRegistryEngineConfiguration configuration,
                                 RestInboundChannelModelProcessor restChannels) {
        configuration.getChannelJsonConverter().addInboundChannelModelClass("rest", RestInboundChannelModel.class);
        configuration.addChannelModelProcessor(restChannels);
        configuration.setNonMatchingEventConsumer(new WorkflowNonMatchingEventConsumer());
        configuration.setFallbackToDefaultTenant(false);
    }
}
```

If `setFallbackToDefaultTenant` is not on `EventRegistryEngineConfiguration` in 8.0.0, delete that line: the default is already `false` (R-C4).

- [ ] **Step 6: Run the test**

Run: `mvn -q -pl workflow-engine test -Dtest=RestInboundChannelTest`

Expected: 2 tests PASS.

- If the second test fails because the deployment succeeds, Flowable is not calling processors during deploy in this setup. Check that `registerChannelModel` runs (put a breakpoint there), then fix the setup. Do not weaken the test.
- If the model is deserialized as plain `InboundChannelModel`, the converter registration ran too late. `customize` must run before `buildProcessEngine`.

- [ ] **Step 7: Commit**

```bash
git add workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/Rest*.java \
        workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/WorkflowEventRegistryCustomizer.java \
        workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/eventregistry/RestInboundChannelTest.java
git commit -m "feat: add the rest inbound channel type"
```

---

### Task 7: Wire the extensions into the Spring engines

**Files:**
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/config/FlowableEventRegistryConfig.java`
- Modify: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/config/FlowableEngineConfig.java`

**Interfaces:**
- Produces: a `RestInboundChannelModelProcessor` bean, injected later by the webhook (slice 4).

- [ ] **Step 1: Event registry engine configurer**

```java
package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.RestInboundChannelModelProcessor;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.WorkflowEventRegistryCustomizer;
import org.flowable.eventregistry.spring.SpringEventRegistryEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlowableEventRegistryConfig {

    /**
     * Flowable's auto-configuration also collects ChannelModelProcessor beans, and the customizer
     * adds this one too. Step 4 checks it ends up registered exactly once.
     */
    @Bean
    public RestInboundChannelModelProcessor restInboundChannelModelProcessor() {
        return new RestInboundChannelModelProcessor();
    }

    @Bean
    public EngineConfigurationConfigurer<SpringEventRegistryEngineConfiguration> workflowEventRegistryConfigurer(
            RestInboundChannelModelProcessor restChannels) {
        return configuration -> WorkflowEventRegistryCustomizer.customize(configuration, restChannels);
    }
}
```

Flowable auto-collects every `ChannelModelProcessor` bean. Because the processor is also exposed as a bean, check after startup (Step 4) that it is registered **once**. If it is registered twice, remove the `addChannelModelProcessor` call from the customizer only when it runs under Spring: add a boolean parameter `addProcessor` to `customize`, pass `false` here and `true` in the test.

- [ ] **Step 2: Process engine consumer**

In `FlowableEngineConfig.processEngineConfigurationConfigurer`, inside the lambda, after `configuration.setCreateDiagramOnDeploy(false);`:

```java
            // Failing subscriptions no longer stop the remaining ones; outcomes go to InboundDispatchContext.
            configuration.setEventRegistryEventConsumer(new WorkflowBpmnEventRegistryEventConsumer(configuration));
```

and the import:

```java
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry.WorkflowBpmnEventRegistryEventConsumer;
```

- [ ] **Step 3: Compile and run the engine module tests**

Run: `mvn -q -pl workflow-engine test`

Expected: BUILD SUCCESS

- [ ] **Step 4: Start the application once**

Run the app against a dev database the usual way (`mvn -pl workflow-boot spring-boot:run` with the dev `.env`).

Expected:
- The application starts.
- The log has no `Not supported inbound channel model type`.
- A debugger or log line in `RestInboundChannelModelProcessor` shows it was added once.

Stop the app.

- [ ] **Step 5: Commit**

```bash
git add workflow-engine/src/main/java/io/github/illuseahashmap/workflow/config/FlowableEventRegistryConfig.java \
        workflow-engine/src/main/java/io/github/illuseahashmap/workflow/config/FlowableEngineConfig.java
git commit -m "feat: install event registry extensions in the Spring engines"
```

---

### Task 8: Schema V40

**Files:**
- Create: `platform-migrations/src/main/resources/db/migration/platform/V40__event_registry_library.sql`

- [ ] **Step 1: Check the next version**

Run: `ls platform-migrations/src/main/resources/db/migration/platform | sort -V | tail -1`

Expected: `V39__workflow_call_reference.sql`. If a higher version exists, use the next free number and rename the file.

- [ ] **Step 2: Write the migration**

```sql
-- Event registry library (spec 2026-11-09-event-registry-d1-design). No inbound event journal:
-- idempotency uses platform_inbox_event.

CREATE TABLE workflow_event_registry_draft (
    tenant_id      VARCHAR(64)   NOT NULL,
    model_type     VARCHAR(16)   NOT NULL,
    model_key      VARCHAR(128)  NOT NULL,
    name           VARCHAR(256)  NOT NULL,
    description    VARCHAR(1024),
    model_json     TEXT          NOT NULL,
    revision       BIGINT        NOT NULL,
    updated_by     VARCHAR(255)  NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_workflow_event_registry_draft PRIMARY KEY (tenant_id, model_type, model_key),
    CONSTRAINT ck_workflow_event_registry_draft_type CHECK (model_type IN ('EVENT', 'CHANNEL'))
);

CREATE TABLE workflow_event_registry_release (
    id                      BIGSERIAL     PRIMARY KEY,
    tenant_id               VARCHAR(64)   NOT NULL,
    model_type              VARCHAR(16)   NOT NULL,
    model_key               VARCHAR(128)  NOT NULL,
    flowable_version        INTEGER,
    flowable_definition_id  VARCHAR(64),
    flowable_deployment_id  VARCHAR(64),
    deployed_json_sha256    CHAR(64),
    action                  VARCHAR(16)   NOT NULL,
    performed_by            VARCHAR(255)  NOT NULL,
    performed_at            TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_workflow_event_registry_release_type CHECK (model_type IN ('EVENT', 'CHANNEL')),
    CONSTRAINT ck_workflow_event_registry_release_action CHECK (action IN ('PUBLISH', 'DELETE'))
);

CREATE INDEX ix_workflow_event_registry_release_key
    ON workflow_event_registry_release (tenant_id, model_type, model_key, performed_at DESC);

CREATE TABLE workflow_event_reference (
    tenant_id              VARCHAR(64)   NOT NULL,
    process_definition_id  VARCHAR(128)  NOT NULL,
    activity_id            VARCHAR(255)  NOT NULL,
    event_key              VARCHAR(128)  NOT NULL,
    CONSTRAINT pk_workflow_event_reference PRIMARY KEY (tenant_id, process_definition_id, activity_id)
);

CREATE INDEX ix_workflow_event_reference_event
    ON workflow_event_reference (tenant_id, event_key);

ALTER TABLE workflow_event_registry_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_event_registry_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_workflow_event_registry_draft ON workflow_event_registry_draft
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE workflow_event_registry_release ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_event_registry_release FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_workflow_event_registry_release ON workflow_event_registry_release
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE workflow_event_reference ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_event_reference FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_workflow_event_reference ON workflow_event_reference
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));
```

- [ ] **Step 3: Run the migration tests**

Run: `mvn -q -pl platform-migrations,workflow-boot -am verify -DskipTests=false`

Expected: BUILD SUCCESS. Existing migration tests (Testcontainers) apply V40 cleanly. If the module has a migration naming or RLS test (for example one that checks every tenant table has a policy), it must pass without exclusions.

- [ ] **Step 4: Commit**

```bash
git add platform-migrations/src/main/resources/db/migration/platform/V40__event_registry_library.sql
git commit -m "feat: add event registry library tables"
```

---

### Task 9: Deployment guard (slice 1 behaviour)

**Files:**
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/EventRegistryDeploymentGuard.java`
- Modify: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ProcessDeployer.java`
- Modify: `workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ProcessPublishServiceImplTest.java`
- Test: `workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/EventRegistryDeploymentGuardTest.java`

**Interfaces:**
- Produces: `EventRegistryDeploymentGuard#check(String bpmnXml)`, which throws `BusinessException(BAD_REQUEST)`.

Slice 1 rules:
1. Any element with a `flowable:eventType`, `flowable:triggerEventType`, or `flowable:type="send-event"` is rejected with "chưa được hỗ trợ". Slice 5 replaces this rule with real validation.
2. A `receiveTask` with both `workflow:agentTask` and `flowable:eventType` is rejected (R-H2). Rule 1 already catches it; the specific message is kept for slice 5.
3. A `receiveTask` that is the target of a sequence flow from an `eventBasedGateway` is rejected (R-H1). This also applies to manual waits.

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

class EventRegistryDeploymentGuardTest {

    private final EventRegistryDeploymentGuard guard = new EventRegistryDeploymentGuard();

    static String bpmn(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:activiti="http://activiti.org/bpmn"
                             xmlns:workflow="http://workflow-agent.illuseahashmap.github.io/schema/bpmn"
                             targetNamespace="t">
                  <process id="p" isExecutable="true">
                    <startEvent id="s"/>
                    %s
                  </process>
                </definitions>""".formatted(body);
    }

    private void rejects(String body, String message) {
        assertThatThrownBy(() -> guard.check(bpmn(body)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(message);
    }

    @Test
    void acceptsManualWaitAndAsyncLeave() {
        assertThatCode(() -> guard.check(bpmn("""
                <receiveTask id="wait" flowable:asyncLeave="true"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>""")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsEventWaitsUntilSliceFive() {
        rejects("""
                <receiveTask id="wait"><extensionElements>
                  <flowable:eventType>paymentReceived</flowable:eventType>
                </extensionElements></receiveTask>""", "wait");
        rejects("""
                <intermediateCatchEvent id="catch"><extensionElements>
                  <activiti:eventType>paymentReceived</activiti:eventType>
                </extensionElements></intermediateCatchEvent>""", "catch");
        rejects("""
                <boundaryEvent id="b" attachedToRef="wait"><extensionElements>
                  <flowable:eventType>paymentReceived</flowable:eventType>
                </extensionElements></boundaryEvent>""", "b");
    }

    @Test
    void rejectsEventStartAndSendEvent() {
        rejects("""
                <startEvent id="es"><extensionElements>
                  <flowable:eventType>paymentReceived</flowable:eventType>
                </extensionElements></startEvent>""", "es");
        rejects("<serviceTask id=\"send\" flowable:type=\"send-event\"/>", "send");
        rejects("""
                <serviceTask id="sr" flowable:type="send-event" flowable:triggerable="true">
                  <extensionElements><flowable:triggerEventType>reply</flowable:triggerEventType></extensionElements>
                </serviceTask>""", "sr");
    }

    @Test
    void rejectsReceiveTaskAfterEventBasedGateway() {
        rejects("""
                <eventBasedGateway id="g"/>
                <receiveTask id="wait"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="g"/>
                <sequenceFlow id="f2" sourceRef="g" targetRef="wait"/>""", "wait");
    }
}
```

Before running, look up the real `workflow:` namespace URI in `XmlAgentTaskBindingParser` and use it in `bpmn(...)`. The test does not depend on it today, but slice 5 will.

- [ ] **Step 2: Run it and see it fail**

Run: `mvn -q -pl workflow-engine test -Dtest=EventRegistryDeploymentGuardTest`

Expected: compilation failure.

- [ ] **Step 3: Implement the guard**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Event registry elements on a published diagram. Slices 1–4 reject them all: the event library,
 * channels and designer panels they depend on do not exist yet (spec 2026-11-09, slice 5).
 */
@Component
public class EventRegistryDeploymentGuard {

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final Set<String> FLOWABLE_NS = Set.of("http://flowable.org/bpmn", "http://activiti.org/bpmn");
    private static final Set<String> EVENT_ELEMENTS = Set.of("eventType", "triggerEventType");

    public void check(String bpmnXml) {
        Document document = parse(bpmnXml);
        NodeList all = document.getElementsByTagNameNS("*", "*");
        for (int index = 0; index < all.getLength(); index++) {
            Element element = (Element) all.item(index);
            if (FLOWABLE_NS.contains(element.getNamespaceURI()) && EVENT_ELEMENTS.contains(element.getLocalName())) {
                Element owner = owner(element);
                String id = owner == null ? "?" : owner.getAttribute("id");
                if (owner != null && "receiveTask".equals(owner.getLocalName()) && hasAgentTask(owner)) {
                    throw invalid("Bước " + id + " không thể vừa là tác vụ Agent vừa chờ sự kiện");
                }
                throw invalid("Bước " + id + " dùng sự kiện (Event Registry), tính năng này chưa được hỗ trợ");
            }
            if (BPMN_NS.equals(element.getNamespaceURI()) && "serviceTask".equals(element.getLocalName())
                    && "send-event".equals(flowableAttribute(element, "type"))) {
                throw invalid("Bước " + element.getAttribute("id") + " gửi sự kiện, tính năng này chưa được hỗ trợ");
            }
        }
        rejectReceiveTasksAfterEventGateways(document);
    }

    private static void rejectReceiveTasksAfterEventGateways(Document document) {
        Set<String> gateways = new HashSet<>();
        NodeList gatewayNodes = document.getElementsByTagNameNS(BPMN_NS, "eventBasedGateway");
        for (int index = 0; index < gatewayNodes.getLength(); index++) {
            gateways.add(((Element) gatewayNodes.item(index)).getAttribute("id"));
        }
        if (gateways.isEmpty()) {
            return;
        }
        Set<String> receiveTasks = new HashSet<>();
        NodeList receiveNodes = document.getElementsByTagNameNS(BPMN_NS, "receiveTask");
        for (int index = 0; index < receiveNodes.getLength(); index++) {
            receiveTasks.add(((Element) receiveNodes.item(index)).getAttribute("id"));
        }
        NodeList flows = document.getElementsByTagNameNS(BPMN_NS, "sequenceFlow");
        for (int index = 0; index < flows.getLength(); index++) {
            Element flow = (Element) flows.item(index);
            String target = flow.getAttribute("targetRef");
            if (gateways.contains(flow.getAttribute("sourceRef")) && receiveTasks.contains(target)) {
                throw invalid("Bước chờ " + target + " không đặt được sau cổng rẽ theo sự kiện."
                        + " Hãy dùng sự kiện chờ (intermediate catch event)");
            }
        }
    }

    /** The BPMN element that owns an extension element: extensionElements' parent. */
    private static Element owner(Element extension) {
        if (extension.getParentNode() instanceof Element parent
                && "extensionElements".equals(parent.getLocalName())
                && parent.getParentNode() instanceof Element owner) {
            return owner;
        }
        return null;
    }

    private static boolean hasAgentTask(Element receiveTask) {
        NodeList children = receiveTask.getElementsByTagNameNS("*", "agentTask");
        return children.getLength() > 0;
    }

    private static String flowableAttribute(Element element, String name) {
        for (String ns : FLOWABLE_NS) {
            String value = element.getAttributeNS(ns, name);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static Document parse(String bpmnXml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(bpmnXml)));
        } catch (Exception exception) {
            throw invalid("BPMN XML could not be read");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
```

- [ ] **Step 4: Run the guard test**

Run: `mvn -q -pl workflow-engine test -Dtest=EventRegistryDeploymentGuardTest`

Expected: 4 tests PASS.

- [ ] **Step 5: Run the guard in `ProcessDeployer`**

Add a constructor parameter `EventRegistryDeploymentGuard eventRegistryDeploymentGuard` right after `CallActivityDeploymentGuard callActivityDeploymentGuard`, store it in a field, and call it in `deployChecked` right after `subProcessDeploymentGuard.check(xml);`:

```java
        eventRegistryDeploymentGuard.check(xml);
```

In `ProcessPublishServiceImplTest`, pass `new EventRegistryDeploymentGuard()` at the same position in `new ProcessDeployer(...)`.

Run `grep -rn "new ProcessDeployer(" workflow-engine workflow-boot` and update every other construction the same way.

- [ ] **Step 6: Run the engine module tests**

Run: `mvn -q -pl workflow-engine test`

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/EventRegistryDeploymentGuard.java \
        workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ProcessDeployer.java \
        workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/EventRegistryDeploymentGuardTest.java \
        workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ProcessPublishServiceImplTest.java
git commit -m "feat: block event registry elements until the event library exists"
```

---

## Slice 2 — Manual wait step

### Task 10: Wait-state classification

**Files:**
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/WaitStateKinds.java`
- Test: `workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/WaitStateKindsTest.java`

**Interfaces:**
- Produces: `WaitStateKinds.isManualWait(FlowElement)` and `WaitStateKinds.isEventWait(FlowElement)`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;

class WaitStateKindsTest {

    private static ReceiveTask receiveTask(String... extensionNames) {
        ReceiveTask task = new ReceiveTask();
        task.setId("wait");
        for (String name : extensionNames) {
            ExtensionElement extension = new ExtensionElement();
            extension.setName(name);
            extension.setElementText("x");
            task.addExtensionElement(extension);
        }
        return task;
    }

    @Test
    void onlyPlainReceiveTasksAreManualWaits() {
        assertThat(WaitStateKinds.isManualWait(receiveTask())).isTrue();
        assertThat(WaitStateKinds.isManualWait(receiveTask("agentTask"))).isFalse();
        assertThat(WaitStateKinds.isManualWait(receiveTask("eventType"))).isFalse();
        assertThat(WaitStateKinds.isManualWait(new UserTask())).isFalse();
        assertThat(WaitStateKinds.isManualWait(new ServiceTask())).isFalse();
        assertThat(WaitStateKinds.isManualWait(null)).isFalse();
    }

    @Test
    void eventWaitNeedsANonBlankEventType() {
        assertThat(WaitStateKinds.isEventWait(receiveTask("eventType"))).isTrue();
        ReceiveTask blank = receiveTask();
        ExtensionElement empty = new ExtensionElement();
        empty.setName("eventType");
        empty.setElementText(" ");
        blank.addExtensionElement(empty);
        assertThat(WaitStateKinds.isEventWait(blank)).isFalse();
        assertThat(List.of(WaitStateKinds.isEventWait(receiveTask()))).containsExactly(false);
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `mvn -q -pl workflow-engine test -Dtest=WaitStateKindsTest`

Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import java.util.List;
import org.flowable.bpmn.constants.BpmnXMLConstants;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ReceiveTask;

/** Which kind of wait a receive task is. Mirrors ReceiveTaskParseHandler in Flowable 8.0.0. */
public final class WaitStateKinds {

    private static final String AGENT_TASK = "agentTask";

    private WaitStateKinds() {
    }

    /** A receive task only an explicit trigger continues: no Agent binding, no event type (R-F1). */
    public static boolean isManualWait(FlowElement element) {
        return element instanceof ReceiveTask task
                && !task.getExtensionElements().containsKey(AGENT_TASK)
                && !isEventWait(task);
    }

    /** A receive task Flowable turns into ReceiveEventTaskActivityBehavior. */
    public static boolean isEventWait(FlowElement element) {
        if (!(element instanceof ReceiveTask task)) {
            return false;
        }
        List<ExtensionElement> types = task.getExtensionElements().get(BpmnXMLConstants.ELEMENT_EVENT_TYPE);
        return types != null && !types.isEmpty()
                && types.getFirst().getElementText() != null
                && !types.getFirst().getElementText().isBlank();
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -q -pl workflow-engine test -Dtest=WaitStateKindsTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/WaitStateKinds.java \
        workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/WaitStateKindsTest.java
git commit -m "feat: classify receive task wait states"
```

---

### Task 11: List and trigger manual waits

**Files:**
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/ReceiveTaskOperationService.java`
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/dto/ProcessWaitView.java`
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/dto/TriggerReceiveTaskRequest.java`
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ReceiveTaskOperationServiceImpl.java`
- Test: `workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ReceiveTaskOperationServiceImplTest.java`

**Interfaces:**
- Consumes: `WaitStateKinds` (Task 10), `ProcessInstanceTransactionExecutor#execute(String, Supplier<T>)`, `WorkflowOperationAuditService#record(...)`, `ProcessVariablePolicy.clientVariables(Map)`, `TenantProvider`.
- Produces:
  - `List<ProcessWaitView> waits(String processInstanceId)`.
  - `void trigger(TriggerReceiveTaskRequest request)`.
  - `ProcessWaitView(String kind, String activityId, String activityName, String executionId, OffsetDateTime since)`, where `kind = "MANUAL"`.
  - `TriggerReceiveTaskRequest(@NotBlank processInstanceId, @NotBlank activityId, String executionId, Map<String,Object> variables, @NotBlank reason)`.

- [ ] **Step 1: DTOs and interface**

```java
package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;

/** A step the instance is currently waiting at. Slice 2 only reports manual waits. */
public record ProcessWaitView(
        String kind,
        String activityId,
        String activityName,
        String executionId,
        OffsetDateTime since
) {
    public static final String MANUAL = "MANUAL";
}
```

```java
package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record TriggerReceiveTaskRequest(
        @NotBlank String processInstanceId,
        @NotBlank String activityId,
        String executionId,
        Map<String, Object> variables,
        @NotBlank String reason
) {
}
```

```java
package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.ProcessWaitView;
import io.github.illuseahashmap.workflow.process.application.dto.TriggerReceiveTaskRequest;
import java.util.List;

/** Manual wait steps (plain receive tasks): who is waiting where, and continuing one. */
public interface ReceiveTaskOperationService {

    List<ProcessWaitView> waits(String processInstanceId);

    void trigger(TriggerReceiveTaskRequest request);
}
```

- [ ] **Step 2: Write the failing test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.TriggerReceiveTaskRequest;
import io.github.illuseahashmap.workflow.process.infrastructure.lock.ProcessInstanceTransactionExecutor;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReceiveTaskOperationServiceImplTest {

    private static final String TENANT = "tenant-wait";
    private static final TenantProvider TENANT_PROVIDER = () -> new TenantContext.TenantInfo(TENANT, "wait", "Wait");
    private static ProcessEngine engine;

    private WorkflowOperationAuditService audit;
    private ReceiveTaskOperationServiceImpl service;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:receive-task-ops;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
        deploy("manual", "<receiveTask id=\"wait\" name=\"Chờ xác nhận\"/>");
        deploy("agentLike", """
                <receiveTask id="wait"><extensionElements>
                  <workflow:agentTask xmlns:workflow="urn:test" agentVersionId="1"/>
                </extensionElements></receiveTask>""");
        deploy("eventWait", """
                <receiveTask id="wait"><extensionElements>
                  <flowable:eventType>paymentReceived</flowable:eventType>
                </extensionElements></receiveTask>""");
        deploy("parallelWait", """
                <receiveTask id="wait">
                  <multiInstanceLoopCharacteristics isSequential="false"><loopCardinality>2</loopCardinality>
                  </multiInstanceLoopCharacteristics>
                </receiveTask>""");
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static void deploy(String key, String step) {
        engine.getRepositoryService().createDeployment().tenantId(TENANT).addString(key + ".bpmn20.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s"/>%s<userTask id="after"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="after"/>
                  </process>
                </definitions>""".formatted(key, step)).deploy();
    }

    private static ProcessInstance start(String key) {
        return engine.getRuntimeService().startProcessInstanceByKeyAndTenantId(key, Map.of(), TENANT);
    }

    private static boolean waiting(ProcessInstance instance) {
        return engine.getRuntimeService().createActivityInstanceQuery()
                .processInstanceId(instance.getId()).activityId("wait").unfinished().count() > 0;
    }

    @BeforeEach
    void setUp() {
        audit = mock(WorkflowOperationAuditService.class);
        ProcessInstanceTransactionExecutor executor = mock(ProcessInstanceTransactionExecutor.class);
        when(executor.execute(anyString(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        service = new ReceiveTaskOperationServiceImpl(engine.getRuntimeService(), engine.getRepositoryService(),
                engine.getHistoryService(), executor, TENANT_PROVIDER, audit);
    }

    private static TriggerReceiveTaskRequest request(ProcessInstance instance, String executionId,
                                                     Map<String, Object> variables) {
        return new TriggerReceiveTaskRequest(instance.getId(), "wait", executionId, variables, "Đã gọi điện xác nhận");
    }

    @Test
    void listsManualWaitsOnly() {
        ProcessInstance manual = start("manual");
        ProcessInstance agent = start("agentLike");
        ProcessInstance event = start("eventWait");

        assertThat(service.waits(manual.getId())).singleElement().satisfies(wait -> {
            assertThat(wait.kind()).isEqualTo("MANUAL");
            assertThat(wait.activityId()).isEqualTo("wait");
            assertThat(wait.activityName()).isEqualTo("Chờ xác nhận");
            assertThat(wait.executionId()).isNotBlank();
            assertThat(wait.since()).isNotNull();
        });
        assertThat(service.waits(agent.getId())).isEmpty();
        assertThat(service.waits(event.getId())).isEmpty();
    }

    @Test
    void triggersAManualWaitWithVariablesAndAudits() {
        ProcessInstance instance = start("manual");

        service.trigger(request(instance, null, Map.of("confirmed", true)));

        assertThat(waiting(instance)).isFalse();
        assertThat(engine.getRuntimeService().getVariable(instance.getId(), "confirmed")).isEqualTo(true);
        verify(audit).record(eq("RECEIVE_TASK_TRIGGERED"), eq(instance.getId()), eq("manual"), eq(null),
                eq("wait"), eq("WAITING"), eq("TRIGGERED"), contains("variables=confirmed"));
    }

    @Test
    void refusesAgentAndEventWaits() {
        for (String key : List.of("agentLike", "eventWait")) {
            ProcessInstance instance = start(key);
            assertThatThrownBy(() -> service.trigger(request(instance, null, Map.of())))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
                    .hasMessageContaining("không phải bước chờ thủ công");
            assertThat(waiting(instance)).isTrue();
        }
    }

    @Test
    void multiInstanceNeedsAnExecutionId() {
        ProcessInstance instance = start("parallelWait");
        assertThatThrownBy(() -> service.trigger(request(instance, null, Map.of())))
                .hasMessageContaining("executionId");

        String first = service.waits(instance.getId()).getFirst().executionId();
        service.trigger(request(instance, first, Map.of()));

        assertThat(service.waits(instance.getId())).hasSize(1);
    }

    @Test
    void rejectsReservedVariablesUnknownInstancesAndOtherTenants() {
        ProcessInstance instance = start("manual");
        assertThatThrownBy(() -> service.trigger(request(instance, null, Map.of("tenantId", "x"))))
                .hasMessageContaining("tenantId");
        assertThatThrownBy(() -> service.trigger(new TriggerReceiveTaskRequest(
                "missing", "wait", null, Map.of(), "r")))
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        // Definitions exist only for TENANT: the tenant filter in the service relies on this.
        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("manual"))
                .isInstanceOf(org.flowable.common.engine.api.FlowableObjectNotFoundException.class);
    }
}
```

- [ ] **Step 3: Run it and see it fail**

Run: `mvn -q -pl workflow-engine test -Dtest=ReceiveTaskOperationServiceImplTest`

Expected: compilation failure, `ReceiveTaskOperationServiceImpl` missing.

- [ ] **Step 4: Implement**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.ProcessVariablePolicy;
import io.github.illuseahashmap.workflow.process.application.ReceiveTaskOperationService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessWaitView;
import io.github.illuseahashmap.workflow.process.application.dto.TriggerReceiveTaskRequest;
import io.github.illuseahashmap.workflow.process.infrastructure.lock.ProcessInstanceTransactionExecutor;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ActivityInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;

@Service
public class ReceiveTaskOperationServiceImpl implements ReceiveTaskOperationService {

    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;
    private final ProcessInstanceTransactionExecutor transactionExecutor;
    private final TenantProvider tenantProvider;
    private final WorkflowOperationAuditService auditService;

    public ReceiveTaskOperationServiceImpl(RuntimeService runtimeService, RepositoryService repositoryService,
                                           HistoryService historyService,
                                           ProcessInstanceTransactionExecutor transactionExecutor,
                                           TenantProvider tenantProvider, WorkflowOperationAuditService auditService) {
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.historyService = historyService;
        this.transactionExecutor = transactionExecutor;
        this.tenantProvider = tenantProvider;
        this.auditService = auditService;
    }

    @Override
    public List<ProcessWaitView> waits(String processInstanceId) {
        String id = processInstanceId.trim();
        ProcessInstance instance = requireRunning(tenantProvider.current().tenantId(), id);
        BpmnModel model = repositoryService.getBpmnModel(instance.getProcessDefinitionId());
        return manualWaits(id, model, null).stream()
                .map(activity -> new ProcessWaitView(ProcessWaitView.MANUAL, activity.getActivityId(),
                        activity.getActivityName(), activity.getExecutionId(), toOffset(activity.getStartTime())))
                .toList();
    }

    @Override
    public void trigger(TriggerReceiveTaskRequest request) {
        String id = request.processInstanceId().trim();
        String activityId = request.activityId().trim();
        transactionExecutor.execute(id, () -> {
            ProcessInstance instance = requireRunning(tenantProvider.current().tenantId(), id);
            BpmnModel model = repositoryService.getBpmnModel(instance.getProcessDefinitionId());
            if (!WaitStateKinds.isManualWait(model.getFlowElement(activityId))) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "Bước " + activityId + " không phải bước chờ thủ công");
            }
            List<ActivityInstance> waiting = manualWaits(id, model, activityId);
            if (waiting.isEmpty()) {
                throw new BusinessException(ErrorCode.CONFLICT, "Công việc không đang chờ ở bước " + activityId);
            }
            String executionId = pickExecution(waiting, request.executionId(), activityId);
            Map<String, Object> variables = ProcessVariablePolicy.clientVariables(request.variables());
            runtimeService.trigger(executionId, variables);
            String names = variables.isEmpty() ? "" : " | variables=" + String.join(",", new TreeSet<>(variables.keySet()));
            auditService.record("RECEIVE_TASK_TRIGGERED", id, instance.getProcessDefinitionKey(), null,
                    activityId, "WAITING", "TRIGGERED", request.reason().trim() + names);
            return null;
        });
    }

    private List<ActivityInstance> manualWaits(String processInstanceId, BpmnModel model, String activityId) {
        var query = runtimeService.createActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .activityType("receiveTask")
                .unfinished();
        if (activityId != null) {
            query.activityId(activityId);
        }
        return query.list().stream()
                .filter(activity -> WaitStateKinds.isManualWait(model.getFlowElement(activity.getActivityId())))
                .sorted((left, right) -> left.getStartTime().compareTo(right.getStartTime()))
                .toList();
    }

    private static String pickExecution(List<ActivityInstance> waiting, String requested, String activityId) {
        if (requested != null && !requested.isBlank()) {
            return waiting.stream().map(ActivityInstance::getExecutionId)
                    .filter(requested.trim()::equals).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                            "executionId không thuộc bước " + activityId + " đang chờ"));
        }
        if (waiting.size() > 1) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Bước " + activityId + " có " + waiting.size() + " nhánh đang chờ, hãy chọn executionId");
        }
        return waiting.getFirst().getExecutionId();
    }

    private ProcessInstance requireRunning(String tenantId, String processInstanceId) {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceTenantId(tenantId)
                .processInstanceId(processInstanceId)
                .singleResult();
        if (instance != null) {
            return instance;
        }
        if (historyService.createHistoricProcessInstanceQuery()
                .processInstanceTenantId(tenantId).processInstanceId(processInstanceId).count() > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Process instance is already finished");
        }
        throw new BusinessException(ErrorCode.NOT_FOUND, "Process instance does not exist");
    }

    private static OffsetDateTime toOffset(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
```

Before committing, check how other read services convert `Date` (for example `WorkflowInstanceReadService`). If they use a shared helper or UTC, use the same one so the web shows consistent times.

- [ ] **Step 5: Run the test**

Run: `mvn -q -pl workflow-engine test -Dtest=ReceiveTaskOperationServiceImplTest`

Expected: 5 tests PASS.

If the multi-instance test finds 3 executions, the MI root has an activity instance. Filter out executions whose `isMultiInstanceRoot()` is true by reading them through `createExecutionQuery().executionId(...)`, and add an assertion on the count of 2.

- [ ] **Step 6: Commit**

```bash
git add workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/ReceiveTaskOperationService.java \
        workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/dto/ProcessWaitView.java \
        workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/dto/TriggerReceiveTaskRequest.java \
        workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ReceiveTaskOperationServiceImpl.java \
        workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ReceiveTaskOperationServiceImplTest.java
git commit -m "feat: list and continue manual wait steps"
```

---

### Task 12: REST endpoints

**Files:**
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/interfaces/rest/ReceiveTaskController.java`

- [ ] **Step 1: Implement**

```java
package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.process.application.ReceiveTaskOperationService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessWaitView;
import io.github.illuseahashmap.workflow.process.application.dto.TriggerReceiveTaskRequest;
import io.github.illuseahashmap.workflow.shared.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
```

Take the `ApiResponse` import from `WorkflowManagementController` if the package differs.

- [ ] **Step 2: Check the security surface**

Search `SecurityConfig` and any EIM sitemap or permission seed for `/workflow/management/process/instance/terminate`. If that path is listed explicitly anywhere (CSRF rules, path permission tables, service client `allowed_paths` docs), add both new paths next to it the same way.

- [ ] **Step 3: Run the backend tests**

Run: `mvn -q -pl workflow-engine,workflow-boot -am test`

Expected: BUILD SUCCESS, including the ArchUnit rules. A controller in `interfaces.rest` depending on an `application` interface follows the existing layering.

- [ ] **Step 4: Commit**

```bash
git add workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/interfaces/rest/ReceiveTaskController.java
git commit -m "feat: expose manual wait endpoints"
```

---

### Task 13: Moddle `asyncLeave` and wait-step model helpers (web)

**Files:**
- Modify: `src/bpmn/flowableModdle.ts`
- Create: `src/bpmn/waitTaskModel.ts`
- Test: `src/bpmn/waitTaskModel.spec.ts`

**Interfaces:**
- Produces:
  - `isManualWaitElement(object?: ModdleElement): boolean`.
  - `hasTimerBoundary(shape?: { attachers?: BpmnElement[] }): boolean`.
  - `waitTaskWarnings(form: { asyncLeave: boolean; hasTimeout: boolean }): string[]`.

- [ ] **Step 1: Write the failing test**

```ts
import { BpmnModdle } from 'bpmn-moddle'
import { describe, expect, it } from 'vitest'
import flowableModdle from '@/bpmn/flowableModdle'
import workflowModdle from '@/bpmn/workflowModdle'
import type { ModdleElement } from '@/bpmn/modeler-types'
import { hasTimerBoundary, isManualWaitElement, waitTaskWarnings } from './waitTaskModel'

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="test">
  <bpmn:process id="p">
    <bpmn:receiveTask id="wait" flowable:asyncLeave="true" />
    <bpmn:receiveTask id="eventWait">
      <bpmn:extensionElements><flowable:eventType>paid</flowable:eventType></bpmn:extensionElements>
    </bpmn:receiveTask>
  </bpmn:process>
</bpmn:definitions>`

async function load() {
  const moddle = new BpmnModdle({ flowable: flowableModdle, workflow: workflowModdle })
  const { rootElement } = await moddle.fromXML(XML)
  const process = (rootElement as ModdleElement).rootElements as ModdleElement[]
  const elements = process[0]!.flowElements as ModdleElement[]
  return { moddle, rootElement, byId: (id: string) => elements.find((e) => e.id === id)! }
}

describe('wait task model', () => {
  it('round-trips flowable:asyncLeave', async () => {
    const { moddle, rootElement, byId } = await load()
    expect(byId('wait').asyncLeave).toBe(true)
    const { xml } = await moddle.toXML(rootElement)
    expect(xml).toContain('flowable:asyncLeave="true"')
    expect(xml).toContain('<flowable:eventType>paid</flowable:eventType>')
  })

  it('tells manual waits apart from agent and event waits', async () => {
    const { byId } = await load()
    expect(isManualWaitElement(byId('wait'))).toBe(true)
    expect(isManualWaitElement(byId('eventWait'))).toBe(false)
    expect(
      isManualWaitElement({
        $type: 'bpmn:ReceiveTask',
        extensionElements: { values: [{ $type: 'workflow:AgentTask' }] },
      }),
    ).toBe(false)
    expect(isManualWaitElement({ $type: 'bpmn:UserTask' })).toBe(false)
  })

  it('detects a timer boundary and warns', () => {
    const timer = {
      businessObject: { eventDefinitions: [{ $type: 'bpmn:TimerEventDefinition' }] },
    }
    expect(hasTimerBoundary({ attachers: [timer] })).toBe(true)
    expect(hasTimerBoundary({ attachers: [] })).toBe(false)
    expect(waitTaskWarnings({ asyncLeave: true, hasTimeout: true })).toEqual([])
    expect(waitTaskWarnings({ asyncLeave: false, hasTimeout: false })).toHaveLength(2)
  })
})
```

Check that `src/bpmn/workflowModdle.ts` has a default export of the `workflow` descriptor (the designer already registers it). Adjust the import if not.

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest --run src/bpmn/waitTaskModel.spec.ts`

Expected: FAIL, `./waitTaskModel` not found.

- [ ] **Step 3: Add the moddle type**

In `src/bpmn/flowableModdle.ts`, add as the first entry of `types`:

```ts
    {
      name: 'AsyncLeave',
      extends: ['bpmn:Activity'],
      properties: [{ name: 'asyncLeave', isAttr: true, type: 'Boolean' }],
    },
```

- [ ] **Step 4: Implement the helpers**

```ts
import type { BpmnElement, ModdleElement } from './modeler-types'

function extensionTypes(object?: ModdleElement) {
  return (object?.extensionElements?.values || []).map((value) => String(value.$type || '').toLowerCase())
}

/** A plain receive task: continued only by "Tiếp tục". Agent and event waits are not manual. */
export function isManualWaitElement(object?: ModdleElement) {
  if (object?.$type !== 'bpmn:ReceiveTask') return false
  const types = extensionTypes(object)
  return !types.includes('workflow:agenttask') && !types.includes('flowable:eventtype')
}

export function hasTimerBoundary(shape?: { attachers?: BpmnElement[] }) {
  return (shape?.attachers || []).some((attacher) =>
    ((attacher.businessObject?.eventDefinitions as ModdleElement[] | undefined) || []).some(
      (definition) => definition.$type === 'bpmn:TimerEventDefinition',
    ),
  )
}

export function waitTaskWarnings(form: { asyncLeave: boolean; hasTimeout: boolean }) {
  const warnings: string[] = []
  if (!form.hasTimeout) {
    warnings.push('Bước chờ chưa có hạn chờ. Công việc có thể chờ mãi nếu không ai bấm tiếp tục.')
  }
  if (!form.asyncLeave) {
    warnings.push(
      'Đã tắt "Rời bước bất đồng bộ": lỗi ở các bước phía sau sẽ làm lệnh tiếp tục thất bại theo.',
    )
  }
  return warnings
}
```

- [ ] **Step 5: Run the test**

Run: `pnpm vitest --run src/bpmn/waitTaskModel.spec.ts`

Expected: 3 tests PASS. Also run `pnpm vitest --run src/bpmn` so the existing round-trip specs still pass with the new type.

- [ ] **Step 6: Commit**

```bash
git add src/bpmn/flowableModdle.ts src/bpmn/waitTaskModel.ts src/bpmn/waitTaskModel.spec.ts
git commit -m "feat(web): model manual wait steps and asyncLeave"
```

---

### Task 14: Palette entry, panel and designer wiring (web)

**Files:**
- Modify: `src/bpmn/workflowPaletteModule.ts`
- Create: `src/bpmn/workflowPaletteModule.spec.ts`
- Create: `src/features/process-definition/components/ReceiveTaskConfigPanel.vue`
- Modify: `src/features/process-definition/views/ProcessDesignerView.vue`

- [ ] **Step 1: Write the failing palette test**

First export the provider class: change `class WorkflowPaletteProvider {` to `export class WorkflowPaletteProvider {`.

```ts
import { describe, expect, it, vi } from 'vitest'
import { WorkflowPaletteProvider } from './workflowPaletteModule'

describe('workflow palette', () => {
  it('offers a wait step that leaves asynchronously', () => {
    const palette = { registerProvider: vi.fn() }
    const create = { start: vi.fn() }
    const elementFactory = { createShape: vi.fn((attrs) => attrs) }
    const bpmnFactory = { create: vi.fn((type, attrs) => ({ $type: type, ...attrs })) }
    const provider = new WorkflowPaletteProvider(palette, create, elementFactory, bpmnFactory)

    const entries = provider.getPaletteEntries()({})
    const entry = entries['create.wait-task']!
    expect(entry.title).toBe('Thêm bước chờ')
    expect(entry.className).toBe('bpmn-icon-receive-task')

    entry.action.click(new Event('click'))
    expect(bpmnFactory.create).toHaveBeenCalledWith('bpmn:ReceiveTask', { asyncLeave: true })
    expect(create.start).toHaveBeenCalledWith(
      expect.any(Event),
      expect.objectContaining({ type: 'bpmn:ReceiveTask' }),
    )
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest --run src/bpmn/workflowPaletteModule.spec.ts`

Expected: FAIL, no `create.wait-task` entry.

- [ ] **Step 3: Add the palette entry**

In `getPaletteEntries`, after the `create.task` entry:

```ts
      const createWaitTask = (event: Event) => {
        const businessObject = this.bpmnFactory.create('bpmn:ReceiveTask', { asyncLeave: true })
        const shape = this.elementFactory.createShape({ type: 'bpmn:ReceiveTask', businessObject })
        this.create.start(event, shape)
      }

      entries['create.wait-task'] = {
        group: 'activity',
        className: 'bpmn-icon-receive-task',
        title: 'Thêm bước chờ',
        action: { dragstart: createWaitTask, click: createWaitTask },
      }
```

Run the palette test again. Expected: PASS.

- [ ] **Step 4: Create the panel**

```vue
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { BpmnElement, BpmnModelerInstance, Modeling } from '@/bpmn/modeler-types'
import { hasTimerBoundary, waitTaskWarnings } from '@/bpmn/waitTaskModel'

interface HostShape extends BpmnElement {
  x?: number
  y?: number
  width?: number
  height?: number
  attachers?: BpmnElement[]
}
interface ShapeFactory {
  createShape(attrs: { type: string; eventDefinitionType?: string }): BpmnElement
}
interface ShapeModeling extends Modeling {
  createShape(
    shape: BpmnElement,
    position: { x: number; y: number },
    target: BpmnElement,
    hints?: { attach?: boolean },
  ): BpmnElement
}

const props = defineProps<{ element: BpmnElement; modeler: BpmnModelerInstance }>()

const asyncLeave = ref(readAsyncLeave())
const revision = ref(0)
const host = computed(() => props.element as HostShape)
const hasTimeout = computed(() => revision.value >= 0 && hasTimerBoundary(host.value))
const warnings = computed(() =>
  waitTaskWarnings({ asyncLeave: asyncLeave.value, hasTimeout: hasTimeout.value }),
)

watch(
  () => props.element,
  () => {
    asyncLeave.value = readAsyncLeave()
    revision.value++
  },
)

function readAsyncLeave() {
  return props.element.businessObject?.asyncLeave === true
}

function applyAsyncLeave(value: boolean) {
  asyncLeave.value = value
  const modeling = props.modeler.get('modeling') as Modeling
  modeling.updateProperties(props.element, { asyncLeave: value ? true : undefined })
}

function addTimeout() {
  const factory = props.modeler.get('elementFactory') as ShapeFactory
  const modeling = props.modeler.get('modeling') as ShapeModeling
  const shape = host.value
  const boundary = factory.createShape({
    type: 'bpmn:BoundaryEvent',
    eventDefinitionType: 'bpmn:TimerEventDefinition',
  })
  modeling.createShape(
    boundary,
    { x: (shape.x ?? 0) + (shape.width ?? 100) - 18, y: (shape.y ?? 0) + (shape.height ?? 80) },
    shape,
    { attach: true },
  )
  revision.value++
}
</script>

<template>
  <div class="property-block">
    <strong>Bước chờ</strong>
    <p class="property-hint">
      Công việc dừng ở đây cho tới khi người vận hành hoặc hệ thống bấm "Tiếp tục" trên màn chi tiết
      công việc.
    </p>
    <el-form label-position="top" size="small">
      <el-form-item>
        <el-checkbox
          :model-value="asyncLeave"
          @update:model-value="applyAsyncLeave(Boolean($event))"
        >
          Rời bước bất đồng bộ
        </el-checkbox>
      </el-form-item>
      <el-form-item label="Hạn chờ">
        <span v-if="hasTimeout" class="property-hint">
          Đã có hạn chờ. Chọn sự kiện hẹn giờ gắn trên bước để sửa thời gian.
        </span>
        <el-button v-else size="small" @click="addTimeout">Thêm hạn chờ</el-button>
      </el-form-item>
    </el-form>
    <el-alert
      v-for="warning in warnings"
      :key="warning"
      :title="warning"
      type="warning"
      :closable="false"
      show-icon
    />
  </div>
</template>
```

- [ ] **Step 5: Wire it into the designer**

In `src/features/process-definition/views/ProcessDesignerView.vue`:

1. Imports:

```ts
import ReceiveTaskConfigPanel from '../components/ReceiveTaskConfigPanel.vue'
import { isManualWaitElement } from '@/bpmn/waitTaskModel'
```

2. Next to `const isScriptTask = …`:

```ts
const isManualWait = computed(() => isManualWaitElement(selectedBusinessObject.value))
```

3. After the `<ScriptTaskConfigPanel … />` block:

```vue
            <ReceiveTaskConfigPanel
              v-if="isManualWait && selectedElement && modeler"
              :element="selectedElement"
              :modeler="modeler"
            />
```

4. A wait step is not an approval step. Change

```vue
v-if="showsApprovalMode(isTask, isServiceTask || isScriptTask)"
```

to

```vue
v-if="showsApprovalMode(isTask, isServiceTask || isScriptTask || selectedType === 'bpmn:ReceiveTask')"
```

5. In `getElementTypeLabel`, add `'bpmn:ReceiveTask': 'Bước chờ',`.

- [ ] **Step 6: Type-check, lint and run the web tests**

Run: `pnpm type-check && pnpm lint && pnpm vitest --run`

Expected: all PASS. If `architecture.spec.ts` forbids `features/*` importing from `@/bpmn`, check how `SubProcessConfigPanel.vue` imports `@/bpmn/subProcessModel` and follow the same rule.

- [ ] **Step 7: Manual check in the designer**

Run `pnpm dev` and open the designer:

1. Drag "Thêm bước chờ". The panel shows "Bước chờ", the checkbox is ticked, and there is a warning about the missing timeout.
2. Click "Thêm hạn chờ". A timer appears on the bottom edge, the warning disappears, and the existing event panel edits the duration.
3. Untick the checkbox. A second warning appears. Save. The XML has no `flowable:asyncLeave` attribute.
4. Tick again and save. The XML has `flowable:asyncLeave="true"`.
5. Publish against the dev backend. It succeeds.

- [ ] **Step 8: Commit**

```bash
git add src/bpmn/workflowPaletteModule.ts src/bpmn/workflowPaletteModule.spec.ts \
        src/features/process-definition/components/ReceiveTaskConfigPanel.vue \
        src/features/process-definition/views/ProcessDesignerView.vue
git commit -m "feat(web): add the wait step to the designer"
```

---

### Task 15: "Đang chờ" on the instance detail (web)

**Files:**
- Modify: `src/features/process-instance/types.ts`
- Modify: `src/features/process-instance/api.ts`
- Modify: `src/api/queryKeys.ts`
- Create: `src/features/process-instance/waitVariables.ts`
- Test: `src/features/process-instance/waitVariables.spec.ts`
- Create: `src/features/process-instance/components/ProcessWaitsPanel.vue`
- Modify: `src/features/process-instance/views/ProcessInstanceDetailView.vue`

**Interfaces:**
- Produces:
  - `processInstanceApi.waits(id)` and `processInstanceApi.triggerReceiveTask(payload)`.
  - `queryKeys.processInstanceWaits(tenantCode, id)`.
  - `parseWaitVariables(text: string): Record<string, unknown>`.

- [ ] **Step 1: Write the failing helper test**

```ts
import { describe, expect, it } from 'vitest'
import { parseWaitVariables } from './waitVariables'

describe('parseWaitVariables', () => {
  it('accepts empty text and JSON objects', () => {
    expect(parseWaitVariables('')).toEqual({})
    expect(parseWaitVariables('  ')).toEqual({})
    expect(parseWaitVariables('{"approved": true, "note": "ok"}')).toEqual({ approved: true, note: 'ok' })
  })

  it('rejects anything that is not a JSON object', () => {
    expect(() => parseWaitVariables('[1]')).toThrow('đối tượng JSON')
    expect(() => parseWaitVariables('42')).toThrow('đối tượng JSON')
    expect(() => parseWaitVariables('{oops')).toThrow('đối tượng JSON')
  })
})
```

- [ ] **Step 2: Run it and see it fail**

Run: `pnpm vitest --run src/features/process-instance/waitVariables.spec.ts`

Expected: FAIL, module missing.

- [ ] **Step 3: Implement the helper**

```ts
const MESSAGE = 'Biến phải là một đối tượng JSON, ví dụ {"approved": true}'

/** Variables typed by the operator when continuing a wait step. Empty means none. */
export function parseWaitVariables(text: string): Record<string, unknown> {
  if (!text.trim()) return {}
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    throw new Error(MESSAGE)
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(MESSAGE)
  return value as Record<string, unknown>
}
```

Run the test. Expected: PASS.

- [ ] **Step 4: Types, API and query key**

`src/features/process-instance/types.ts`:

```ts
export interface ProcessWait {
  kind: 'MANUAL'
  activityId: string
  activityName?: string
  executionId: string
  since?: string
}

export interface TriggerReceiveTaskRequest {
  processInstanceId: string
  activityId: string
  executionId?: string
  variables?: Record<string, unknown>
  reason: string
}
```

`src/features/process-instance/api.ts`, inside `processInstanceApi` after `terminate`. Add `ProcessWait` and `TriggerReceiveTaskRequest` to the type import.

```ts
  waits: (processInstanceId: string) =>
    apiClient.get<ProcessWait[]>(`${ROOT}/instance/${encodeURIComponent(processInstanceId)}/waits`),
  triggerReceiveTask: (payload: TriggerReceiveTaskRequest) =>
    apiClient.post<void>(`${ROOT}/instance/receive-task/trigger`, payload),
```

`src/api/queryKeys.ts`, after `processInstanceDiagram`:

```ts
  processInstanceWaits: (tenantCode: string, id: string) =>
    [...queryKeys.processInstances(tenantCode), 'waits', id] as const,
```

- [ ] **Step 5: The panel**

```vue
<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { getErrorMessage } from '@/api/http'
import { queryKeys } from '@/api/queryKeys'
import { formatDateTime } from '@/utils/format'
import { processInstanceApi } from '../api'
import type { ProcessWait } from '../types'
import { parseWaitVariables } from '../waitVariables'

const props = defineProps<{
  processInstanceId: string
  tenantCode: string
  canOperate: boolean
}>()
const emit = defineEmits<{ triggered: [] }>()

const waitsQuery = useQuery({
  queryKey: computed(() => queryKeys.processInstanceWaits(props.tenantCode, props.processInstanceId)),
  queryFn: () => processInstanceApi.waits(props.processInstanceId),
})
const waits = computed(() => waitsQuery.data.value ?? [])

const dialogVisible = ref(false)
const selected = ref<ProcessWait>()
const form = reactive({ reason: '', variables: '' })
const formError = ref('')

const triggerMutation = useMutation({
  mutationFn: () => {
    const wait = selected.value
    if (!wait) throw new Error('Chưa chọn bước chờ')
    if (!form.reason.trim()) throw new Error('Hãy nhập lý do')
    return processInstanceApi.triggerReceiveTask({
      processInstanceId: props.processInstanceId,
      activityId: wait.activityId,
      executionId: wait.executionId,
      variables: parseWaitVariables(form.variables),
      reason: form.reason.trim(),
    })
  },
  onSuccess: async () => {
    dialogVisible.value = false
    ElMessage.success('Đã cho bước chờ tiếp tục')
    await waitsQuery.refetch()
    emit('triggered')
  },
  onError: (error) => {
    formError.value = getErrorMessage(error)
  },
})

function open(wait: ProcessWait) {
  selected.value = wait
  Object.assign(form, { reason: '', variables: '' })
  formError.value = ''
  dialogVisible.value = true
}
</script>

<template>
  <div v-loading="waitsQuery.isFetching.value">
    <el-table :data="waits" height="470" empty-text="Không có bước chờ thủ công nào">
      <el-table-column label="Bước" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.activityName || row.activityId }}</template>
      </el-table-column>
      <el-table-column prop="activityId" label="Mã bước" min-width="150" />
      <el-table-column label="Đang chờ từ" min-width="170">
        <template #default="{ row }">{{ formatDateTime(row.since) }}</template>
      </el-table-column>
      <el-table-column v-if="canOperate" label="Thao tác" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">Tiếp tục</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="Tiếp tục bước chờ" width="520px">
      <el-form label-position="top">
        <el-form-item label="Bước">
          <span>{{ selected?.activityName || selected?.activityId }}</span>
        </el-form-item>
        <el-form-item label="Lý do" required>
          <el-input v-model="form.reason" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="Biến gửi kèm (JSON, không bắt buộc)">
          <el-input
            v-model="form.variables"
            type="textarea"
            :rows="4"
            placeholder='{"approved": true}'
          />
        </el-form-item>
      </el-form>
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon />
      <template #footer>
        <el-button @click="dialogVisible = false">Hủy</el-button>
        <el-button
          type="primary"
          :loading="triggerMutation.isPending.value"
          @click="formError = ''; triggerMutation.mutate()"
        >
          Tiếp tục
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
```

- [ ] **Step 6: Add the tab**

In `ProcessInstanceDetailView.vue`:

1. Import: `import ProcessWaitsPanel from '../components/ProcessWaitsPanel.vue'`.
2. After the "Tiến độ" `el-tab-pane`:

```vue
        <el-tab-pane v-if="instance?.status === 'RUNNING'" label="Đang chờ" name="waits">
          <ProcessWaitsPanel
            v-if="activeTab === 'waits'"
            :process-instance-id="id"
            :tenant-code="tenantCode"
            :can-operate="canOperateInstances"
            @triggered="refresh"
          />
        </el-tab-pane>
```

`instance`, `id`, `tenantCode`, `canOperateInstances` and `refresh` already exist in the view. Check the names before pasting.

- [ ] **Step 7: Type-check, lint and run the web tests**

Run: `pnpm type-check && pnpm lint && pnpm vitest --run`

Expected: all PASS.

- [ ] **Step 8: Manual check end to end**

With the dev backend, publish a process `start → Bước chờ → user task → end` and start an instance. On its detail page:

1. The "Đang chờ" tab lists the wait step with its start time.
2. Click "Tiếp tục" with an empty reason. The dialog shows "Hãy nhập lý do".
3. Enter `[1]` as variables. The dialog shows the JSON object message.
4. Enter a reason and `{"approved": true}`. You get a success toast, the tab empties, the diagram moves to the user task, and the variables tab shows `approved = true`.
5. Log in as a `WF_VIEWER`. The tab is visible but there is no "Tiếp tục" column.
6. Check `workflow_operation_audit`. There is a `RECEIVE_TASK_TRIGGERED` row whose reason ends with `variables=approved` and has no value.

- [ ] **Step 9: Commit**

```bash
git add src/features/process-instance/types.ts src/features/process-instance/api.ts src/api/queryKeys.ts \
        src/features/process-instance/waitVariables.ts src/features/process-instance/waitVariables.spec.ts \
        src/features/process-instance/components/ProcessWaitsPanel.vue \
        src/features/process-instance/views/ProcessInstanceDetailView.vue
git commit -m "feat(web): continue manual wait steps from the instance detail"
```

---

### Task 16: Full verification

- [ ] **Step 1: Backend**

Run: `mvn verify` from `workflow-agent-service`

Expected: BUILD SUCCESS, including SpotBugs, JaCoCo thresholds and the ArchUnit tests.

If SpotBugs flags the mutable lists exposed by `RestInboundChannelModel` getters (`EI_EXPOSE_REP`), keep the getters: Flowable and Jackson need them. Add a narrowly scoped exclusion for that class only.

- [ ] **Step 2: Web**

Run: `pnpm check` from `workflow-agent-web` (or `pnpm type-check && pnpm lint && pnpm vitest --run` if `check` does not exist)

Expected: PASS

- [ ] **Step 3: Update the spec status**

In `docs/superpowers/specs/2026-11-09-event-registry-d1-design.md`, under «Lát triển khai», mark slices 1 and 2 as done, with the date and the commit range.

- [ ] **Step 4: Commit any fixes**

```bash
git add -A
git commit -m "chore: verify event registry slices 1 and 2"
```
