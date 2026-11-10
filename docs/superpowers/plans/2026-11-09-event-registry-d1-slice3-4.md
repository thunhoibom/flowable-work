# Event Registry D1 — Slices 3–4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- **Slice 3:** a designer can create, edit, publish and delete **event models** per tenant. Publishing deploys a Flowable 8 `.event` definition, but only when its content changed. Published models accept only compatible changes.
- **Slice 4:** a designer can define **REST inbound channels**. An external system posts events to `POST /platform-api/channel-definitions/key/{channelKey}/events`. Each event goes through Flowable's own inbound pipeline, with idempotency, tenant taken from the caller's identity, and explicit response codes.

**Spec:** `docs/superpowers/specs/2026-11-09-event-registry-d1-design.md` (risk ids R-xx: `2026-11-09-receive-task-research.md`). Slices 1–2 plan: `2026-11-09-event-registry-d1-slice1-2.md`.

**Architecture:**
- **New feature package `io.github.illuseahashmap.workflow.eventregistry` in `workflow-engine`:**
  - `domain`: records and rules, framework-free.
  - `application`: service interfaces, DTOs, ports.
  - `infrastructure`: JDBC repositories, Flowable gateway, service implementations.
  - `interfaces.rest`: controllers.
- **Drafts** live in `workflow_event_registry_draft` (V40), with `model_type` set to `EVENT` or `CHANNEL`. **Releases** live in `workflow_event_registry_release`.
- **Flowable is the source of truth for what is published.** The previous model comes from `EventRepositoryService#getEventModelByKey(key, tenantId)`.
- **Deployments** are named `event:<key>` or `channel:<key>` and carry the tenant.
- **The webhook** finds the registered `RestInboundChannelModel` through `RestInboundChannelModelProcessor` (slice 1). It runs the channel's pipeline itself, so it can validate each `EventInstance` before handing it to `EventRegistry#sendEventToConsumers` inside an `InboundDispatchContext`. Idempotency uses `platform_inbox_event`.

**Tech Stack:** Java 25, Spring Boot 4.1, Flowable 8.0.0, Jackson 2 (`com.fasterxml`, as the rest of `workflow-engine`), JdbcTemplate, JUnit 5, AssertJ, Mockito, H2 for engine-only tests, Testcontainers PostgreSQL for integration, Vue 3, Vitest, Element Plus, `@tanstack/vue-query`.

## Global Constraints

- Do **not** commit (user decision, 2026-11-09). Skip every commit step and list the changed files instead.
- Do not edit lines a teammate changed in the working tree. Append only, in `application.yml`, `.env.example`, `workflow-agent-web/.env.example` and `src/config/product.ts`.
- Keys: event key and channel key both match `^[A-Za-z][A-Za-z0-9_-]{0,63}$` (spec, updated 2026-11-09). Field names match `^[A-Za-z][A-Za-z0-9_]{0,63}$`.
- Field types are the Flowable 8 `EventPayloadTypes`: `string`, `integer`, `long`, `double`, `boolean`, `json`.
- Event model rules:
  - At least 1 and at most 3 correlation fields.
  - Correlation fields are `string`, `integer` or `long` only.
  - `fullPayload` only on `json`, at most one field.
  - A field cannot be both `fullPayload` and correlation.
  - At most 64 fields, names unique.
- Compatible change to a published event model: add a non-correlation field, or change name or description. Anything else is `CONFLICT` with a message starting `Thay đổi không tương thích:` that lists every offending field.
- Publish deploys only when the generated Flowable JSON differs from the last published SHA-256 (R-E1).
- Channel rules:
  - Key detection is `FIXED`, `JSON_FIELD` or `JSON_POINTER`.
  - `FIXED` forces `allowedEventKeys = [value]`.
  - At least one allowed event key. Every allowed key must be published in the tenant at channel publish time.
  - At least one entry in `userIds` + `groupIds`, at most 50 each.
  - `channelEventTenantIdDetection.fixedValue` is always the tenant id and is never user-editable.
- Writes (draft, publish, delete) need `workflow.event-registry.enabled=true`. Otherwise they fail with `BAD_REQUEST "Tính năng sự kiện chưa được bật"`. Reads always work. The webhook answers `404 CHANNEL_NOT_FOUND` while the flag is off.
- Roles:
  - Read: `SERVICE, WF_PLATFORM_ADMIN, WF_ADMIN, WF_OPERATOR, WF_VIEWER`.
  - Write: `SERVICE, WF_PLATFORM_ADMIN, WF_ADMIN`.
  - Webhook: any authenticated caller, then the channel's `security` decides.
- Webhook:
  - Tenant comes from `TenantContext`, set by the auth filters, never from the body (R-C4).
  - `Idempotency-Key` must be a UUID.
  - Body is at most `workflow.event-registry.max-body-bytes` (default 262144).
  - Headers `Authorization`, `Cookie` and `X-Workflow-Token` never reach the event.
  - The receiver must **not** be `@Transactional`: each trigger is its own Flowable command (R-D2).
- Idempotency rows: `consumer_name = "event-channel:" + tenantId + ":" + channelKey`, `tenant_code` from the tenant. Keep a row only after at least one success (`MATCHED`/`PARTIAL`), otherwise delete it. In-progress lease is 60 s. Completed rows are deleted after `workflow.event-registry.idempotency-retention` (default `7d`).
- Response codes:
  - `200 MATCHED` / `PARTIAL` / `DUPLICATE`.
  - `400 INVALID_EVENT` (unparsable) / `IDEMPOTENCY_KEY_REQUIRED`.
  - `403 CHANNEL_FORBIDDEN`.
  - `404 CHANNEL_NOT_FOUND`.
  - `409 NO_WAITING_INSTANCE` / `CONCURRENT_UPDATE` / `EVENT_IN_PROGRESS`.
  - `413 EVENT_TOO_LARGE`.
  - `422 EVENT_NOT_ALLOWED` / `INVALID_CORRELATION` / `EVENT_NOT_RECOGNIZED`.
  - `500 EVENT_PROCESSING_FAILED`.
  - `503 CHANNEL_NOT_READY`.
- Logs never contain payload or correlation values.
- Web:
  - The menu entry shows only when `VITE_EVENTS_ENABLED=true`.
  - Menu code `WF_EVENT` must also exist on the EIM sitemap (Task 15).
  - Vietnamese UI text.

Java commands run from `workflow-agent-service`, web commands from `workflow-agent-web`. Maven: use the wrapper distribution in `~/.m2/wrapper/dists` with `-o`. Engine tests need `-pl workflow-engine -am` because `-pl` alone picks stale upstream jars.

---

## Slice 3 — Event model library

### Task 1: Settings for slices 3–4

**Files:**
- Modify: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/config/EventRegistryProperties.java`
- Modify: `workflow-boot/src/main/resources/application.yml` (append under `workflow.event-registry`)
- Modify: `.env.example` (append)

- [ ] **Step 1: Extend the properties**

Add to `EventRegistryProperties`:

```java
    /** Largest webhook body accepted, in bytes. */
    private int maxBodyBytes = 262_144;

    /** How long a completed Idempotency-Key keeps blocking duplicates. */
    private Duration idempotencyRetention = Duration.ofDays(7);

    /** How long an unfinished delivery holds its Idempotency-Key before another attempt may take over. */
    private Duration inProgressLease = Duration.ofSeconds(60);
```

Add getters and setters for the three fields, and `import java.time.Duration;`.

- [ ] **Step 2: Append to `application.yml`** under the existing `event-registry:` block:

```yaml
    max-body-bytes: ${WORKFLOW_EVENT_REGISTRY_MAX_BODY_BYTES:262144}
    idempotency-retention: ${WORKFLOW_EVENT_REGISTRY_IDEMPOTENCY_RETENTION:7d}
```

- [ ] **Step 3: Append to `.env.example`**

```bash
WORKFLOW_EVENT_REGISTRY_MAX_BODY_BYTES=262144
WORKFLOW_EVENT_REGISTRY_IDEMPOTENCY_RETENTION=7d
```

- [ ] **Step 4: Compile**

Run: `mvn -q -o -pl workflow-engine -am compile`. Expected: BUILD SUCCESS.

---

### Task 2: Event model domain and rules

**Files:**
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/eventregistry/domain/EventFieldType.java`
- Create: `…/eventregistry/domain/EventField.java`
- Create: `…/eventregistry/domain/EventModelDefinition.java`
- Create: `…/eventregistry/domain/EventModelRules.java`
- Test: `workflow-engine/src/test/java/io/github/illuseahashmap/workflow/eventregistry/domain/EventModelRulesTest.java`

**Interfaces:**
- Produces:
  - `EventFieldType#flowableName()` and `EventFieldType.fromFlowable(String)`.
  - `record EventField(String name, EventFieldType type, boolean correlation, boolean header, boolean fullPayload)`.
  - `record EventModelDefinition(String key, String name, String description, List<EventField> fields)`.
  - `EventModelRules.validate(EventModelDefinition): List<String>`.
  - `EventModelRules.incompatibilities(List<EventField> published, List<EventField> next): List<String>`.
  - `EventModelRules.KEY` (`Pattern`).

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.workflow.eventregistry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventModelRulesTest {

    static EventField field(String name, EventFieldType type) {
        return new EventField(name, type, false, false, false);
    }

    static EventField correlation(String name, EventFieldType type) {
        return new EventField(name, type, true, false, false);
    }

    static EventModelDefinition model(EventField... fields) {
        return new EventModelDefinition("paymentReceived", "Đã nhận thanh toán", null, List.of(fields));
    }

    @Test
    void acceptsAValidModel() {
        assertThat(EventModelRules.validate(model(
                correlation("orderId", EventFieldType.STRING),
                field("amount", EventFieldType.DOUBLE),
                new EventField("raw", EventFieldType.JSON, false, false, true)))).isEmpty();
    }

    @Test
    void reportsEveryStructuralProblem() {
        List<EventField> tooMany = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            tooMany.add(field("f" + index, EventFieldType.STRING));
        }
        assertThat(EventModelRules.validate(new EventModelDefinition("1bad", " ", null, List.of())))
                .anyMatch(message -> message.contains("Mã sự kiện"))
                .anyMatch(message -> message.contains("Tên"))
                .anyMatch(message -> message.contains("tương quan"));
        assertThat(EventModelRules.validate(new EventModelDefinition("ok", "Ok", null, tooMany)))
                .anyMatch(message -> message.contains("64"));
    }

    @Test
    void correlationFieldsAreLimitedInNumberAndType() {
        assertThat(EventModelRules.validate(model(correlation("a", EventFieldType.DOUBLE))))
                .anyMatch(message -> message.contains("a") && message.contains("string, integer, long"));
        assertThat(EventModelRules.validate(model(
                correlation("a", EventFieldType.STRING), correlation("b", EventFieldType.STRING),
                correlation("c", EventFieldType.LONG), correlation("d", EventFieldType.INTEGER))))
                .anyMatch(message -> message.contains("3"));
    }

    @Test
    void fullPayloadAndNamesAreChecked() {
        assertThat(EventModelRules.validate(model(
                correlation("id", EventFieldType.STRING),
                new EventField("raw", EventFieldType.STRING, false, false, true))))
                .anyMatch(message -> message.contains("raw") && message.contains("json"));
        assertThat(EventModelRules.validate(model(
                correlation("id", EventFieldType.STRING),
                new EventField("a", EventFieldType.JSON, false, false, true),
                new EventField("b", EventFieldType.JSON, false, false, true))))
                .anyMatch(message -> message.contains("một"));
        assertThat(EventModelRules.validate(model(
                correlation("id", EventFieldType.STRING), field("id", EventFieldType.STRING), field("9x", EventFieldType.STRING))))
                .anyMatch(message -> message.contains("trùng"))
                .anyMatch(message -> message.contains("9x"));
    }

    @Test
    void onlyAddingPlainFieldsIsCompatible() {
        List<EventField> published = List.of(correlation("orderId", EventFieldType.STRING), field("amount", EventFieldType.DOUBLE));

        assertThat(EventModelRules.incompatibilities(published, List.of(
                correlation("orderId", EventFieldType.STRING), field("amount", EventFieldType.DOUBLE),
                field("note", EventFieldType.STRING)))).isEmpty();
        assertThat(EventModelRules.incompatibilities(published, List.of(correlation("orderId", EventFieldType.STRING))))
                .containsExactly("Xóa trường amount");
        assertThat(EventModelRules.incompatibilities(published, List.of(
                correlation("orderId", EventFieldType.STRING), field("amount", EventFieldType.LONG))))
                .containsExactly("Đổi kiểu trường amount từ double sang long");
        assertThat(EventModelRules.incompatibilities(published, List.of(
                field("orderId", EventFieldType.STRING), field("amount", EventFieldType.DOUBLE))))
                .containsExactly("Bỏ tương quan của trường orderId");
        assertThat(EventModelRules.incompatibilities(published, List.of(
                correlation("orderId", EventFieldType.STRING), field("amount", EventFieldType.DOUBLE),
                correlation("customerId", EventFieldType.STRING))))
                .containsExactly("Thêm trường tương quan customerId");
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `mvn -q -o -pl workflow-engine -am test -Dtest=EventModelRulesTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true -Dspotbugs.skip=true`

Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package io.github.illuseahashmap.workflow.eventregistry.domain;

import java.util.Arrays;

/** Flowable 8 EventPayloadTypes. */
public enum EventFieldType {
    STRING("string"), INTEGER("integer"), LONG("long"), DOUBLE("double"), BOOLEAN("boolean"), JSON("json");

    private final String flowableName;

    EventFieldType(String flowableName) {
        this.flowableName = flowableName;
    }

    public String flowableName() {
        return flowableName;
    }

    public boolean correlatable() {
        return this == STRING || this == INTEGER || this == LONG;
    }

    public static EventFieldType fromFlowable(String name) {
        return Arrays.stream(values()).filter(type -> type.flowableName.equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown event field type " + name));
    }
}
```

```java
package io.github.illuseahashmap.workflow.eventregistry.domain;

public record EventField(String name, EventFieldType type, boolean correlation, boolean header, boolean fullPayload) {
}
```

```java
package io.github.illuseahashmap.workflow.eventregistry.domain;

import java.util.List;

public record EventModelDefinition(String key, String name, String description, List<EventField> fields) {

    public EventModelDefinition {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
```

```java
package io.github.illuseahashmap.workflow.eventregistry.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Event model rules of spec 2026-11-09-event-registry-d1-design (R-B3, R-B4, R-E2, R-E3). */
public final class EventModelRules {

    public static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
    public static final Pattern FIELD = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    public static final int MAX_FIELDS = 64;
    public static final int MAX_CORRELATIONS = 3;

    private EventModelRules() {
    }

    public static List<String> validate(EventModelDefinition model) {
        List<String> problems = new ArrayList<>();
        if (model.key() == null || !KEY.matcher(model.key()).matches()) {
            problems.add("Mã sự kiện phải khớp " + KEY.pattern());
        }
        if (model.name() == null || model.name().isBlank()) {
            problems.add("Tên sự kiện không được để trống");
        }
        if (model.fields().size() > MAX_FIELDS) {
            problems.add("Sự kiện có tối đa " + MAX_FIELDS + " trường");
        }
        Set<String> seen = new HashSet<>();
        int correlations = 0;
        int fullPayloads = 0;
        for (EventField field : model.fields()) {
            String name = field.name() == null ? "" : field.name();
            if (!FIELD.matcher(name).matches()) {
                problems.add("Tên trường không hợp lệ: " + (name.isEmpty() ? "(trống)" : name));
            } else if (!seen.add(name)) {
                problems.add("Tên trường bị trùng: " + name);
            }
            if (field.type() == null) {
                problems.add("Trường " + name + " chưa có kiểu");
                continue;
            }
            if (field.correlation()) {
                correlations++;
                if (!field.type().correlatable()) {
                    problems.add("Trường tương quan " + name + " chỉ được có kiểu string, integer, long");
                }
            }
            if (field.fullPayload()) {
                fullPayloads++;
                if (field.type() != EventFieldType.JSON) {
                    problems.add("Trường " + name + " nhận toàn bộ nội dung nên phải có kiểu json");
                }
                if (field.correlation()) {
                    problems.add("Trường " + name + " không thể vừa nhận toàn bộ nội dung vừa là tương quan");
                }
            }
        }
        if (correlations == 0) {
            problems.add("Sự kiện cần ít nhất một trường tương quan");
        }
        if (correlations > MAX_CORRELATIONS) {
            problems.add("Sự kiện có tối đa " + MAX_CORRELATIONS + " trường tương quan");
        }
        if (fullPayloads > 1) {
            problems.add("Chỉ một trường được nhận toàn bộ nội dung");
        }
        return problems;
    }

    /** Changes that would break running instances or deployed processes. Empty means compatible. */
    public static List<String> incompatibilities(List<EventField> published, List<EventField> next) {
        Map<String, EventField> after = new LinkedHashMap<>();
        next.forEach(field -> after.put(field.name(), field));
        Set<String> before = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (EventField old : published) {
            before.add(old.name());
            EventField now = after.get(old.name());
            if (now == null) {
                problems.add("Xóa trường " + old.name());
                continue;
            }
            if (now.type() != old.type()) {
                problems.add("Đổi kiểu trường " + old.name() + " từ " + old.type().flowableName()
                        + " sang " + now.type().flowableName());
            }
            if (old.correlation() && !now.correlation()) {
                problems.add("Bỏ tương quan của trường " + old.name());
            }
            if (!old.correlation() && now.correlation()) {
                problems.add("Đặt trường " + old.name() + " thành tương quan");
            }
            if (old.header() != now.header()) {
                problems.add("Đổi nguồn header của trường " + old.name());
            }
            if (old.fullPayload() != now.fullPayload()) {
                problems.add("Đổi cờ nhận toàn bộ nội dung của trường " + old.name());
            }
        }
        after.values().stream()
                .filter(field -> !before.contains(field.name()) && field.correlation())
                .forEach(field -> problems.add("Thêm trường tương quan " + field.name()));
        return problems;
    }
}
```

- [ ] **Step 4: Run the test**

Same command as Step 2. Expected: 5 tests PASS.

---

### Task 3: Flowable JSON for event and channel models

**Files:**
- Create: `workflow-engine/src/main/java/io/github/illuseahashmap/workflow/eventregistry/infrastructure/FlowableEventRegistryJson.java`
- Test: `workflow-engine/src/test/java/io/github/illuseahashmap/workflow/eventregistry/infrastructure/FlowableEventRegistryJsonTest.java`

Channel domain types are created in Task 9. This task only covers event JSON plus a `sha256` helper. Task 9 adds `channel(...)` to this class.

**Interfaces:**
- Produces:
  - `FlowableEventRegistryJson.event(EventModelDefinition): String`. Stable field order, accepted by Flowable's `EventJsonConverter`.
  - `FlowableEventRegistryJson.sha256(String): String`.
  - `FlowableEventRegistryJson.fields(EventModel): List<EventField>`, which reads back from Flowable.

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventFieldType;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventModelDefinition;
import java.util.List;
import org.flowable.eventregistry.json.converter.EventJsonConverter;
import org.flowable.eventregistry.model.EventModel;
import org.junit.jupiter.api.Test;

class FlowableEventRegistryJsonTest {

    private static final EventModelDefinition MODEL = new EventModelDefinition("paymentReceived", "Đã nhận",
            "mô tả", List.of(
                    new EventField("orderId", EventFieldType.STRING, true, false, false),
                    new EventField("source", EventFieldType.STRING, false, true, false),
                    new EventField("raw", EventFieldType.JSON, false, false, true)));

    @Test
    void producesJsonFlowableParsesBackToTheSameFields() {
        String json = FlowableEventRegistryJson.event(MODEL);

        EventModel parsed = new EventJsonConverter().convertToEventModel(json);

        assertThat(parsed.getKey()).isEqualTo("paymentReceived");
        assertThat(parsed.getName()).isEqualTo("Đã nhận");
        assertThat(FlowableEventRegistryJson.fields(parsed)).containsExactlyElementsOf(MODEL.fields());
    }

    @Test
    void sameModelGivesSameHash() {
        assertThat(FlowableEventRegistryJson.sha256(FlowableEventRegistryJson.event(MODEL)))
                .isEqualTo(FlowableEventRegistryJson.sha256(FlowableEventRegistryJson.event(MODEL)))
                .hasSize(64);
    }
}
```

If `EventJsonConverter` has no no-arg constructor or `convertToEventModel(String)` in 8.0.0, read the class in `flowable-event-registry-json-converter-8.0.0-sources.jar` and use its real API. Keep the assertion.

- [ ] **Step 2: Run it and see it fail**

Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventFieldType;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventModelDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.flowable.eventregistry.model.EventModel;

/** The JSON Flowable 8 deploys for event and channel models, built in a stable order. */
public final class FlowableEventRegistryJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private FlowableEventRegistryJson() {
    }

    public static String event(EventModelDefinition model) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("key", model.key());
        root.put("name", model.name());
        ArrayNode payload = root.putArray("payload");
        for (EventField field : model.fields()) {
            ObjectNode node = payload.addObject();
            node.put("name", field.name());
            node.put("type", field.type().flowableName());
            if (field.correlation()) {
                node.put("correlationParameter", true);
            }
            if (field.header()) {
                node.put("header", true);
            }
            if (field.fullPayload()) {
                node.put("isFullPayload", true);
            }
        }
        return root.toString();
    }

    public static List<EventField> fields(EventModel model) {
        return model.getPayload().stream()
                .map(payload -> new EventField(payload.getName(), EventFieldType.fromFlowable(payload.getType()),
                        payload.isCorrelationParameter(), payload.isHeader(), payload.isFullPayload()))
                .toList();
    }

    public static String sha256(String json) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
```

- [ ] **Step 4: Run the test**

Expected: 2 tests PASS.

---

### Task 4: Draft and release repositories

**Files:**
- Create: `…/eventregistry/domain/RegistryModelType.java` (`EVENT`, `CHANNEL`)
- Create: `…/eventregistry/domain/RegistryDraft.java`
- Create: `…/eventregistry/domain/RegistryRelease.java`
- Create: `…/eventregistry/application/port/EventRegistryDraftRepository.java`
- Create: `…/eventregistry/application/port/EventRegistryReleaseRepository.java`
- Create: `…/eventregistry/application/port/EventReferenceReader.java`
- Create: `…/eventregistry/infrastructure/JdbcEventRegistryDraftRepository.java`
- Create: `…/eventregistry/infrastructure/JdbcEventRegistryReleaseRepository.java`
- Create: `…/eventregistry/infrastructure/JdbcEventReferenceReader.java`
- Test: `workflow-boot/src/test/java/io/github/illuseahashmap/workflow/eventregistry/EventRegistryRepositoriesIntegrationTest.java` (Testcontainers, same setup as `EventRegistryWiringIntegrationTest`)

**Interfaces:**
- Produces:
  - `record RegistryDraft(String tenantId, RegistryModelType type, String key, String name, String description, String modelJson, long revision, String updatedBy, OffsetDateTime updatedAt)`, plus `RegistryDraft next(String name, String description, String modelJson, String actor, OffsetDateTime now)`, which returns `revision + 1`.
  - `record RegistryRelease(String tenantId, RegistryModelType type, String key, Integer flowableVersion, String flowableDefinitionId, String flowableDeploymentId, String deployedJsonSha256, String action, String performedBy, OffsetDateTime performedAt)`.
  - `EventRegistryDraftRepository`: `Optional<RegistryDraft> find(tenantId, type, key)`, `findForUpdate(...)`, `boolean insert(RegistryDraft)`, `boolean update(RegistryDraft, long expectedRevision)`, `List<RegistryDraft> list(tenantId, type)`, `void delete(tenantId, type, key)`.
  - `EventRegistryReleaseRepository`: `void record(RegistryRelease)`, `Optional<RegistryRelease> latest(tenantId, type, key)`.
  - `EventReferenceReader`: `long latestProcessDefinitionsUsing(String tenantId, String eventKey)`.

- [ ] **Step 1: Write the repositories**

Follow `JdbcProcessDraftRepository` exactly: `JdbcTemplate`, a `COLUMNS` constant, `DuplicateKeyException` → `false` on insert, `UPDATE … WHERE revision = ?` returning `== 1`, `FOR UPDATE` in `findForUpdate`. Bind `model_type` as `type.name()`.

`latest` orders by `performed_at DESC, id DESC LIMIT 1`.

`JdbcEventReferenceReader`:

```java
    @Override
    public long latestProcessDefinitionsUsing(String tenantId, String eventKey) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM workflow_event_reference reference
                JOIN act_re_procdef definition ON definition.id_ = reference.process_definition_id
                WHERE reference.tenant_id = ? AND reference.event_key = ?
                  AND definition.version_ = (SELECT MAX(latest.version_) FROM act_re_procdef latest
                                             WHERE latest.key_ = definition.key_
                                               AND latest.tenant_id_ = definition.tenant_id_)
                """, Long.class, tenantId, eventKey);
        return count == null ? 0 : count;
    }
```

The table stays empty until slice 5 fills it. The query still runs in slice 3, so the delete check is real once slice 5 lands.

- [ ] **Step 2: Integration test**

Copy the class annotations and `@DynamicPropertySource` from `EventRegistryWiringIntegrationTest`. In each test, wrap DB access in `TrustedDataAccessContext.runAsSystemWorker(...)`, because RLS is active on the V40 tables and the test has no tenant context.

Cover:
- Insert, then insert again: the second returns `false`.
- Update with a stale revision returns `false`.
- `findForUpdate` returns the row.
- `list` filters by type and tenant.
- `latest` returns the newest release.
- `latestProcessDefinitionsUsing` returns 0 for an unknown key.

- [ ] **Step 3: Run it**

Run: `mvn -o -pl workflow-boot -am test -Dtest=EventRegistryRepositoriesIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true -Dspotbugs.skip=true`

Expected: PASS.

---

### Task 5: Flowable gateway for event registry deployments

**Files:**
- Create: `…/eventregistry/application/port/EventRegistryGateway.java`
- Create: `…/eventregistry/infrastructure/FlowableEventRegistryGateway.java`
- Test: `workflow-engine/src/test/java/io/github/illuseahashmap/workflow/eventregistry/infrastructure/FlowableEventRegistryGatewayTest.java` (in-memory engine, as `EventRegistryEngineFactsTest`)

**Interfaces:**
- Produces:
  - `record Deployed(String definitionId, String deploymentId, int version)` (nested in the port).
  - `Deployed deployEvent(String tenantId, String key, String json)`.
  - `Deployed deployChannel(String tenantId, String key, String json)`.
  - `Optional<List<EventField>> publishedEventFields(String tenantId, String key)`.
  - `boolean channelDeployed(String tenantId, String key)`.
  - `void deleteDeployments(String tenantId, RegistryModelType type, String key)`.
  - `long waitingSubscriptions(String tenantId, String eventKey)`.

- [ ] **Step 1: Write the failing test**

Build an in-memory process engine and get `EventRegistryEngineConfiguration` from `getEngineConfigurations()`, as in `EventRegistryEngineFactsTest`. Construct `new FlowableEventRegistryGateway(eventRepositoryService, runtimeService)`.

Assert:
- `deployEvent("t1", "paid", json)` returns version 1. A second call returns version 2.
- `publishedEventFields("t1", "paid")` returns the fields. `publishedEventFields("t2", "paid")` is empty, which proves tenant isolation.
- `deleteDeployments("t1", EVENT, "paid")` makes `publishedEventFields` empty again.
- `waitingSubscriptions` counts a subscription created by a deployed receive event task in tenant `t1`. Deploy that BPMN with `.tenantId("t1")` and start the instance with `startProcessInstanceByKeyAndTenantId`.

- [ ] **Step 2: Implement**

```java
@Component
public class FlowableEventRegistryGateway implements EventRegistryGateway {

    private final EventRepositoryService eventRepositoryService;
    private final RuntimeService runtimeService;

    public FlowableEventRegistryGateway(EventRepositoryService eventRepositoryService, RuntimeService runtimeService) {
        this.eventRepositoryService = eventRepositoryService;
        this.runtimeService = runtimeService;
    }

    @Override
    public Deployed deployEvent(String tenantId, String key, String json) {
        EventDeployment deployment = eventRepositoryService.createDeployment()
                .name("event:" + key).tenantId(tenantId)
                .addEventDefinition(key + ".event", json).deploy();
        EventDefinition definition = eventRepositoryService.createEventDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        return new Deployed(definition.getId(), deployment.getId(), definition.getVersion());
    }

    @Override
    public Deployed deployChannel(String tenantId, String key, String json) {
        EventDeployment deployment = eventRepositoryService.createDeployment()
                .name("channel:" + key).tenantId(tenantId)
                .addChannelDefinition(key + ".channel", json).deploy();
        ChannelDefinition definition = eventRepositoryService.createChannelDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        return new Deployed(definition.getId(), deployment.getId(), definition.getVersion());
    }

    @Override
    public Optional<List<EventField>> publishedEventFields(String tenantId, String key) {
        boolean exists = eventRepositoryService.createEventDefinitionQuery()
                .eventDefinitionKey(key).tenantId(tenantId).count() > 0;
        return exists
                ? Optional.of(FlowableEventRegistryJson.fields(eventRepositoryService.getEventModelByKey(key, tenantId)))
                : Optional.empty();
    }

    @Override
    public boolean channelDeployed(String tenantId, String key) {
        return eventRepositoryService.createChannelDefinitionQuery()
                .channelDefinitionKey(key).tenantId(tenantId).count() > 0;
    }

    @Override
    public void deleteDeployments(String tenantId, RegistryModelType type, String key) {
        String name = (type == RegistryModelType.EVENT ? "event:" : "channel:") + key;
        eventRepositoryService.createDeploymentQuery().deploymentName(name).deploymentTenantId(tenantId).list()
                .forEach(deployment -> eventRepositoryService.deleteDeployment(deployment.getId()));
    }

    @Override
    public long waitingSubscriptions(String tenantId, String eventKey) {
        return runtimeService.createEventSubscriptionQuery().eventType(eventKey).tenantId(tenantId).count();
    }
}
```

Check before relying on them:
- The query methods `deploymentId` and `tenantId` on `EventDefinitionQuery` / `ChannelDefinitionQuery`. Use the `-sources.jar`.
- Whether `EventRepositoryService` is a Spring bean. If not, add a `@Bean` in `FlowableEventRegistryConfig` that reads it from `processEngineConfiguration.getEngineConfigurations()`.

- [ ] **Step 3: Run the test**

Expected: PASS.

---

### Task 6: Event model service

**Files:**
- Create: `…/eventregistry/application/EventModelService.java`
- Create: `…/eventregistry/application/dto/EventFieldView.java` (`name, type, correlation, header, fullPayload`; type as its Flowable name)
- Create: `…/eventregistry/application/dto/EventModelView.java`
- Create: `…/eventregistry/application/dto/SaveEventModelRequest.java`
- Create: `…/eventregistry/infrastructure/EventModelServiceImpl.java`
- Test: `workflow-engine/src/test/java/io/github/illuseahashmap/workflow/eventregistry/infrastructure/EventModelServiceImplTest.java`

**Interfaces:**
- Produces:
  - `EventModelService#list(): List<EventModelView>`
  - `get(key): EventModelView`
  - `saveDraft(key, SaveEventModelRequest): EventModelView`
  - `publish(key, long revision): EventModelView`
  - `delete(key): void`
- `EventModelView(String key, String name, String description, List<EventFieldView> fields, long revision, String updatedBy, OffsetDateTime updatedAt, Integer publishedVersion, OffsetDateTime publishedAt, String publishedBy, List<EventFieldView> publishedFields, boolean changed)`
- `SaveEventModelRequest(long revision, @NotBlank String name, String description, @NotNull List<EventFieldView> fields)`. `revision = 0` creates.

Behaviour (`@Transactional(rollbackFor = Exception.class)` on writes; the event registry engine shares the Spring transaction manager):

| Method | Steps |
|---|---|
| `saveDraft` | flag → build `EventModelDefinition` → `EventModelRules.validate`, problems → `BAD_REQUEST` joined with `; ` → revision 0: insert, `false` → `CONFLICT "Sự kiện đã tồn tại"`; otherwise update with expected revision, `false` → `DRAFT_REVISION_CONFLICT` |
| `publish` | flag → `findForUpdate`, missing → `NOT_FOUND`, stale revision → `DRAFT_REVISION_CONFLICT` → validate → `publishedEventFields` present → `incompatibilities` non-empty → `CONFLICT "Thay đổi không tương thích: a; b. Hãy tạo mã sự kiện mới."` → JSON + SHA → latest release is `PUBLISH` with same SHA → return view, no deploy → `deployEvent` → record release `PUBLISH` → audit `EVENT_MODEL_PUBLISHED` (`subject = key`, reason `version=N`) |
| `delete` | flag → `waitingSubscriptions > 0` → `CONFLICT "Còn N công việc đang chờ sự kiện này"` → `latestProcessDefinitionsUsing > 0` → `CONFLICT "Quy trình đang dùng sự kiện này"` → any CHANNEL draft whose `allowedEventKeys` contains the key → `CONFLICT "Kênh X đang dùng sự kiện này"` (no channel drafts exist until slice 4; the check just reads the draft table) → `deleteDeployments` → delete draft → if there was a release, record `DELETE` → audit `EVENT_MODEL_DELETED` |

`model_json` for an event draft is `{"fields":[{"name":…,"type":"string","correlation":true,"header":false,"fullPayload":false}]}`, read and written with the shared `ObjectMapper` bean.

`changed` is true when there is no release, or when the SHA of the draft's JSON differs from the latest `PUBLISH` release.

- [ ] **Step 1: Write the failing test**

Use an in-memory engine (as in Task 5), the real `FlowableEventRegistryGateway`, and in-memory fakes for the three ports. Add them to `InMemoryProcessRepositories` or a new `InMemoryEventRegistryRepositories` next to the test. Use `WorkflowOperationAuditService` as a mock, and a `TenantProvider` and `CurrentPrincipalProvider` as lambdas.

Cover:
1. Save, then publish, deploys version 1. Publishing again unchanged does not deploy: the Flowable version count stays 1.
2. Publish after adding a plain field deploys version 2.
3. Publish after removing a field fails with `Thay đổi không tương thích` and leaves version 2 deployed.
4. Delete while an instance is waiting fails with `Còn 1 công việc`.
5. With the flag off, `saveDraft` fails with `Tính năng sự kiện chưa được bật`, and `list()` still works.
6. Stale revision fails with `DRAFT_REVISION_CONFLICT`.

- [ ] **Step 2: Implement** following the table above. Reuse the tenant, actor and `now()` helpers in the same style as `ProcessPublishServiceImpl`: UTC `OffsetDateTime`, actor is the username or the principal id.

- [ ] **Step 3: Run the test**

Expected: 6 tests PASS.

---

### Task 7: Event model REST API

**Files:**
- Create: `…/eventregistry/interfaces/rest/EventModelController.java`

```java
@RestController
@RequestMapping("/workflow/management/event-registry/events")
public class EventModelController {

    private static final String READ = "hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')";
    private static final String WRITE = "hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')";

    private final EventModelService events;

    public EventModelController(EventModelService events) {
        this.events = events;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<EventModelView>> list() {
        return ApiResponse.ok(events.list());
    }

    @GetMapping("/{key}")
    @PreAuthorize(READ)
    public ApiResponse<EventModelView> get(@PathVariable String key) {
        return ApiResponse.ok(events.get(key));
    }

    @PutMapping("/{key}/draft")
    @PreAuthorize(WRITE)
    public ApiResponse<EventModelView> saveDraft(@PathVariable String key,
                                                 @Valid @RequestBody SaveEventModelRequest request) {
        return ApiResponse.ok(events.saveDraft(key, request));
    }

    @PostMapping("/{key}/publish")
    @PreAuthorize(WRITE)
    public ApiResponse<EventModelView> publish(@PathVariable String key, @RequestParam long revision) {
        return ApiResponse.ok(events.publish(key, revision));
    }

    @DeleteMapping("/{key}")
    @PreAuthorize(WRITE)
    public ApiResponse<Void> delete(@PathVariable String key) {
        events.delete(key);
        return ApiResponse.ok();
    }
}
```

`PreAuthorize` needs a compile-time constant. If the build rejects the `static final` reference, inline the strings.

- [ ] **Step 1: Run the backend tests**

Run: `mvn -o -pl workflow-engine,workflow-boot -am test -Djacoco.skip=true -Dspotbugs.skip=true`

Expected: BUILD SUCCESS, ArchUnit included. The controller depends only on `application` types, not `domain`.

---

### Task 8: "Sự kiện" screen (web)

**Files:**
- Modify: `src/config/product.ts` (append)
- Modify: `workflow-agent-web/.env.example` (append `VITE_EVENTS_ENABLED=false`)
- Modify: `src/router/meta.d.ts` (add `'events'` to `NavigationIcon`)
- Modify: `src/layouts/AppShell.vue` (icon map: `events: RadioTower`, imported from `@lucide/vue`)
- Modify: `src/router/index.ts`
- Create: `src/features/event-registry/api.ts`
- Create: `src/features/event-registry/eventModelDraft.ts`
- Test: `src/features/event-registry/eventModelDraft.spec.ts`
- Create: `src/features/event-registry/EventRegistryView.vue`
- Create: `src/features/event-registry/EventModelPanel.vue`
- Test: `src/features/event-registry/EventModelPanel.spec.ts`

- [ ] **Step 1: Flag, icon and route**

`product.ts`, appended:

```ts
/** Shows the "Sự kiện" screen. The backend flag workflow.event-registry.enabled still decides. */
export const eventsEnabled = import.meta.env.VITE_EVENTS_ENABLED === 'true'
```

Router entry after `services`, gated like `agents`:

```ts
        {
          path: 'events',
          name: 'event-registry',
          component: () => import('@/features/event-registry/EventRegistryView.vue'),
          meta: {
            title: 'Sự kiện',
            requiredAnyRoles: ROLES.designers,
            ...(eventsEnabled
              ? { navigation: { menuCode: 'WF_EVENT', label: 'Sự kiện', icon: 'events' as const, order: 36 } }
              : {}),
          },
        },
```

In the global guard, next to the `agent-management` redirect, add the same redirect for `event-registry` when `!eventsEnabled`.

If `RadioTower` is not exported by the installed `@lucide/vue`, pick another icon from that package (`grep -o "export { [A-Za-z]* " node_modules/@lucide/vue/dist/*.d.ts`).

- [ ] **Step 2: API types**

```ts
import { apiClient } from '@/api/http'

export type EventFieldType = 'string' | 'integer' | 'long' | 'double' | 'boolean' | 'json'

export interface EventFieldView {
  name: string
  type: EventFieldType
  correlation: boolean
  header: boolean
  fullPayload: boolean
}

export interface EventModelView {
  key: string
  name: string
  description?: string
  fields: EventFieldView[]
  revision: number
  updatedBy: string
  updatedAt: string
  publishedVersion?: number
  publishedAt?: string
  publishedBy?: string
  publishedFields?: EventFieldView[]
  changed: boolean
}

const EVENTS = '/workflow/management/event-registry/events'

export const eventRegistryApi = {
  events: () => apiClient.get<EventModelView[]>(EVENTS),
  saveEvent: (key: string, payload: { revision: number; name: string; description?: string; fields: EventFieldView[] }) =>
    apiClient.put<EventModelView>(`${EVENTS}/${encodeURIComponent(key)}/draft`, payload),
  publishEvent: (key: string, revision: number) =>
    apiClient.post<EventModelView>(`${EVENTS}/${encodeURIComponent(key)}/publish`, null, { params: { revision } }),
  deleteEvent: (key: string) => apiClient.delete<void>(`${EVENTS}/${encodeURIComponent(key)}`),
}
```

Check `apiClient.put`/`delete`/`post(url, body, config)` signatures in `src/api/http.ts` and match them.

- [ ] **Step 3: Draft helpers, test first**

`eventModelDraft.spec.ts` must prove that `validateEventModel` and `incompatibleChanges` return the **same messages** as `EventModelRules` for the cases in Task 2. Copy those cases: same inputs, same Vietnamese strings.

`eventModelDraft.ts` exports:
- `FIELD_TYPES`, `blankField()`
- `validateEventModel(form: { key: string; name: string; fields: EventFieldView[] }): string[]`
- `incompatibleChanges(published: EventFieldView[], next: EventFieldView[]): string[]`

Port them line by line from the Java rules. The server stays the authority; the web copy only gives early feedback.

- [ ] **Step 4: Panel**

`EventModelPanel.vue` follows the layout of `ServiceCatalogView.vue`: a list on the left, the selected model on the right. It shows:
- **Header:** key, name, description; badges "Đã phát hành vN" and "Có thay đổi chưa phát hành" (`changed`).
- **Field table** (native `<table>` with `<input>`/`<select>`/`<input type="checkbox">`, with `data-field` attributes so the spec can drive it), with columns:
  - Tên trường
  - Kiểu
  - Tương quan
  - Lấy từ header
  - Toàn bộ nội dung
  - Xóa
- **Warnings:** `validateEventModel` problems, and, when published, `incompatibleChanges` against `publishedFields`, as an error list with the hint "Hãy tạo mã sự kiện mới".
- **Buttons:**
  - "Lưu nháp": disabled when there are validation problems.
  - "Phát hành": disabled when there are incompatibilities or `!changed`.
  - "Xóa": confirm, then show the server message on conflict.
- "Tạo sự kiện" dialog: key and name.

`EventModelPanel.spec.ts` covers:
- Adding a field row.
- The save button being disabled while there is no correlation field.
- The incompatibility list appearing when a published field is removed.

Mock `eventRegistryApi` with `vi.mock` and typed `vi.fn<…>()`, which is required by the repo's oxlint rule `require-mock-type-parameters`.

- [ ] **Step 5: View**

`EventRegistryView.vue`: `PageHeader` (eyebrow "Tích hợp", title "Sự kiện", description "Khai báo sự kiện nghiệp vụ và kênh nhận. Bước chờ trong quy trình chỉ chọn sự kiện."), then `el-tabs` with "Sự kiện" (`EventModelPanel`). Task 13 adds "Kênh nhận".

- [ ] **Step 6: Checks**

Run: `npm run type-check && npx vitest --run && npx oxlint src/features/event-registry src/router src/layouts src/config && npx eslint src/features/event-registry src/router src/layouts src/config`

Expected: all PASS. The repo-wide lint already fails on untouched files (spec, "Lát triển khai"), so lint only the changed paths.

---

## Slice 4 — REST channel

### Task 9: Channel domain, rules and JSON

**Files:**
- Create: `…/eventregistry/domain/KeyDetectionMode.java` (`FIXED`, `JSON_FIELD`, `JSON_POINTER`)
- Create: `…/eventregistry/domain/RestChannelDefinition.java`
- Create: `…/eventregistry/domain/RestChannelRules.java`
- Modify: `…/eventregistry/infrastructure/FlowableEventRegistryJson.java` (add `channel(...)`)
- Test: `…/eventregistry/domain/RestChannelRulesTest.java`
- Test: extend `FlowableEventRegistryJsonTest`

**Interfaces:**
- `record RestChannelDefinition(String key, String name, String description, KeyDetectionMode detection, String detectionValue, List<String> allowedEventKeys, List<String> userIds, List<String> groupIds)`. Its compact constructor copies the lists and, when `detection == FIXED`, replaces `allowedEventKeys` with `List.of(detectionValue)`.
- `RestChannelRules.validate(RestChannelDefinition): List<String>`.
- `FlowableEventRegistryJson.channel(RestChannelDefinition, String tenantId): String`.

- [ ] **Step 1: Tests first**

Rules:
- Key pattern; name required; detection value required.
- `JSON_FIELD` value matches `EventModelRules.FIELD`.
- `JSON_POINTER` value starts with `/`.
- At least one allowed key, each matching `EventModelRules.KEY`.
- At least one of `userIds`/`groupIds`; each entry non-blank, at most 128 characters; at most 50 per list.
- `FIXED` copies its value into `allowedEventKeys`.

JSON: parse the result with Flowable's `ChannelJsonConverter` after `addInboundChannelModelClass("rest", RestInboundChannelModel.class)` and assert:
- `RestInboundChannelModel`, `deserializerType == "json"`.
- Key detection by mode (`getFixedValue` / `getJsonField` / `getJsonPointerExpression`).
- `getChannelEventTenantIdDetection().getFixedValue()` equals the tenant id.
- Allowed keys and security as given.

- [ ] **Step 2: Implement `channel(...)`**

```java
    public static String channel(RestChannelDefinition channel, String tenantId) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("key", channel.key());
        root.put("category", "channel");
        root.put("name", channel.name());
        root.put("channelType", "inbound");
        root.put("type", "rest");
        root.put("deserializerType", "json");
        ObjectNode detection = root.putObject("channelEventKeyDetection");
        switch (channel.detection()) {
            case FIXED -> detection.put("fixedValue", channel.detectionValue());
            case JSON_FIELD -> detection.put("jsonField", channel.detectionValue());
            case JSON_POINTER -> detection.put("jsonPointerExpression", channel.detectionValue());
        }
        root.putObject("channelEventTenantIdDetection").put("fixedValue", tenantId);
        channel.allowedEventKeys().forEach(root.putArray("allowedEventKeys")::add);
        ObjectNode security = root.putObject("security");
        channel.userIds().forEach(security.putArray("userIds")::add);
        channel.groupIds().forEach(security.putArray("groupIds")::add);
        return root.toString();
    }
```

- [ ] **Step 3: Run both tests**

Expected: PASS.

---

### Task 10: Channel service and API

**Files:**
- Create: `…/eventregistry/application/RestChannelService.java`
- Create: `…/eventregistry/application/dto/RestChannelView.java`
- Create: `…/eventregistry/application/dto/SaveRestChannelRequest.java`
- Create: `…/eventregistry/infrastructure/RestChannelServiceImpl.java`
- Create: `…/eventregistry/interfaces/rest/RestChannelController.java` (`/workflow/management/event-registry/channels`, same roles and endpoints as Task 7)
- Test: `…/eventregistry/infrastructure/RestChannelServiceImplTest.java`

**Interfaces:**
- `RestChannelView(String key, String name, String description, String detection, String detectionValue, List<String> allowedEventKeys, List<String> userIds, List<String> groupIds, long revision, String updatedBy, OffsetDateTime updatedAt, Integer publishedVersion, OffsetDateTime publishedAt, String publishedBy, boolean changed, String webhookPath)`, where `webhookPath = "/platform-api/channel-definitions/key/" + key + "/events"`.
- `SaveRestChannelRequest(long revision, @NotBlank String name, String description, @NotBlank String detection, @NotBlank String detectionValue, List<String> allowedEventKeys, List<String> userIds, List<String> groupIds)`.

Behaviour:
- Same as Task 6, without compatibility rules.
- Publish also requires every allowed event key to be published in the tenant (`publishedEventFields(...).isPresent()`). Otherwise `CONFLICT "Sự kiện X chưa được phát hành"`.
- Deploy with `deployChannel(tenantId, key, FlowableEventRegistryJson.channel(definition, tenantId))`.
- Audit `EVENT_CHANNEL_PUBLISHED` and `EVENT_CHANNEL_DELETED`.
- `model_json` for a channel draft: `{"detection":"FIXED","detectionValue":"…","allowedEventKeys":[…],"userIds":[…],"groupIds":[…]}`.

Add to `EventModelServiceImpl.delete` (Task 6): the channel-draft check now has real rows. Its test gains the case "event used by a channel cannot be deleted".

- [ ] **Step 1: Test first**, in-memory engine and fakes as in Task 6:
1. Publishing a channel whose event is not published fails.
2. After publishing the event, the channel publishes, and `RestInboundChannelModelProcessor#find(tenant, key)` returns a model with a pipeline. Use the processor from `WorkflowEventRegistryCustomizer`, as `RestInboundChannelTest` does.
3. Publishing unchanged does not redeploy.
4. Delete removes the deployment, and `find` returns empty.
5. Deleting an event used by a channel fails (`EventModelServiceImpl`).

- [ ] **Step 2: Implement, then run the tests**

Expected: PASS.

---

### Task 11: Idempotency on `platform_inbox_event`

**Files:**
- Create: `…/eventregistry/application/port/InboundIdempotency.java`
- Create: `…/eventregistry/infrastructure/JdbcInboundIdempotency.java`
- Create: `…/eventregistry/infrastructure/InboundIdempotencyJanitor.java`
- Test: `workflow-boot/src/test/java/io/github/illuseahashmap/workflow/eventregistry/InboundIdempotencyIntegrationTest.java` (Testcontainers)

**Interfaces:**
- `enum Claim { CLAIMED, DUPLICATE, IN_PROGRESS }`.
- `Claim claim(String consumerName, UUID eventId, String tenantCode, Duration lease)`.
- `void complete(String consumerName, UUID eventId)`.
- `void release(String consumerName, UUID eventId)`, which deletes only while `completed_at IS NULL`.
- `int purgeCompletedBefore(Duration retention)`, which only touches `consumer_name LIKE 'event-channel:%'`.

- [ ] **Step 1: Implement**

```java
    @Override
    public Claim claim(String consumer, UUID eventId, String tenantCode, Duration lease) {
        int inserted = jdbc.update("""
                INSERT INTO platform_inbox_event (event_id, consumer_name, tenant_code, received_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """, eventId, consumer, tenantCode);
        if (inserted == 1) {
            return Claim.CLAIMED;
        }
        Boolean completed = jdbc.query("""
                SELECT completed_at IS NOT NULL FROM platform_inbox_event
                WHERE consumer_name = ? AND event_id = ?
                """, rs -> rs.next() ? rs.getBoolean(1) : null, consumer, eventId);
        if (completed == null) {
            return claim(consumer, eventId, tenantCode, lease); // released between the two statements
        }
        if (completed) {
            return Claim.DUPLICATE;
        }
        int taken = jdbc.update("""
                UPDATE platform_inbox_event SET received_at = CURRENT_TIMESTAMP
                WHERE consumer_name = ? AND event_id = ? AND completed_at IS NULL
                  AND received_at < CURRENT_TIMESTAMP - make_interval(secs => ?)
                """, consumer, eventId, lease.toSeconds());
        return taken == 1 ? Claim.CLAIMED : Claim.IN_PROGRESS;
    }
```

The recursive retry is bounded in practice. Replace it with a loop of at most 3 attempts if SpotBugs or review prefers.

The janitor:

```java
@Component
public class InboundIdempotencyJanitor {

    private final InboundIdempotency idempotency;
    private final EventRegistryProperties properties;

    // constructor

    @Scheduled(fixedDelayString = "${workflow.event-registry.idempotency-cleanup-ms:3600000}")
    public void purge() {
        TrustedDataAccessContext.runAsSystemWorker(
                () -> idempotency.purgeCompletedBefore(properties.getIdempotencyRetention()));
    }
}
```

- [ ] **Step 2: Integration test** (Postgres, tenant context set for the RLS policy on `tenant_code`; the janitor case runs as system worker). Cover:
- First `claim` returns `CLAIMED`.
- A second `claim` within the lease returns `IN_PROGRESS`.
- `claim` after the lease returns `CLAIMED`. Use a lease of `Duration.ZERO`.
- `complete`, then `claim`, returns `DUPLICATE`.
- `release`, then `claim`, returns `CLAIMED`.
- `release` after `complete` does not delete.
- `purgeCompletedBefore(Duration.ZERO)` deletes completed `event-channel:*` rows and leaves an `agent-completion` row alone.

- [ ] **Step 3: Run it**

Expected: PASS.

---

### Task 12: Inbound event receiver and webhook

**Files:**
- Create: `…/eventregistry/application/InboundEventReceiver.java`
- Create: `…/eventregistry/application/dto/InboundEventResult.java`
- Create: `…/eventregistry/infrastructure/InboundEventReceiverImpl.java`
- Create: `…/eventregistry/interfaces/rest/ChannelWebhookController.java`
- Modify: `workflow-boot/src/main/java/io/github/illuseahashmap/workflow/config/SecurityConfig.java`
- Test: `workflow-boot/src/test/java/io/github/illuseahashmap/workflow/eventregistry/InboundEventReceiverIntegrationTest.java`
- Test: `workflow-engine/src/test/java/io/github/illuseahashmap/workflow/eventregistry/interfaces/rest/ChannelWebhookControllerTest.java`

**Interfaces:**
- `InboundEventResult(int httpStatus, String status, String code, String message, List<Delivery> matched, List<Delivery> failed, boolean replayed)` with `record Delivery(String processInstanceId, String activityId, String errorCode)`.
- `InboundEventReceiver#receive(String channelKey, Map<String, String> headers, byte[] body, String idempotencyKey): InboundEventResult`.

- [ ] **Step 1: Receiver**

```java
@Service
public class InboundEventReceiverImpl implements InboundEventReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboundEventReceiverImpl.class);
    private static final Set<String> HIDDEN_HEADERS = Set.of("authorization", "cookie", "x-workflow-token");

    // fields: EventRegistryProperties properties, TenantProvider tenants, CurrentPrincipalProvider principals,
    // RestInboundChannelModelProcessor channels, EventRegistryGateway gateway, EventRegistry eventRegistry,
    // InboundIdempotency idempotency — constructor injection

    /** Not transactional on purpose: every trigger is its own Flowable command (R-D2). */
    @Override
    public InboundEventResult receive(String channelKey, Map<String, String> headers, byte[] body, String key) {
        if (!properties.isEnabled()) {
            return fail(404, "CHANNEL_NOT_FOUND", "Không có kênh " + channelKey);
        }
        TenantContext.TenantInfo tenant = tenants.current();
        Optional<RestInboundChannelModel> found = channels.find(tenant.tenantId(), channelKey);
        if (found.isEmpty()) {
            return gateway.channelDeployed(tenant.tenantId(), channelKey)
                    ? fail(503, "CHANNEL_NOT_READY", "Kênh đang được nạp, hãy thử lại sau")
                    : fail(404, "CHANNEL_NOT_FOUND", "Không có kênh " + channelKey);
        }
        RestInboundChannelModel channel = found.get();
        if (!allowed(channel.getSecurity(), principals.current())) {
            return fail(403, "CHANNEL_FORBIDDEN", "Không có quyền gửi vào kênh " + channelKey);
        }
        UUID eventId;
        try {
            eventId = UUID.fromString(key == null ? "" : key.trim());
        } catch (IllegalArgumentException exception) {
            return fail(400, "IDEMPOTENCY_KEY_REQUIRED", "Header Idempotency-Key phải là UUID");
        }
        String consumer = "event-channel:" + tenant.tenantId() + ":" + channelKey;
        switch (idempotency.claim(consumer, eventId, tenant.tenantCode(), properties.getInProgressLease())) {
            case DUPLICATE -> {
                return new InboundEventResult(200, "DUPLICATE", null, null, List.of(), List.of(), true);
            }
            case IN_PROGRESS -> {
                return fail(409, "EVENT_IN_PROGRESS", "Sự kiện này đang được xử lý");
            }
            case CLAIMED -> { }
        }
        InboundEventResult result = dispatch(tenant, channel, headers, body);
        if ("MATCHED".equals(result.status()) || "PARTIAL".equals(result.status())) {
            idempotency.complete(consumer, eventId);
        } else {
            idempotency.release(consumer, eventId);
        }
        LOGGER.info("Inbound event tenant={} channel={} idempotencyKey={} status={} matched={} failed={}",
                tenant.tenantId(), channelKey, eventId, result.status(), result.matched().size(), result.failed().size());
        return result;
    }

    private InboundEventResult dispatch(TenantContext.TenantInfo tenant, RestInboundChannelModel channel,
                                        Map<String, String> headers, byte[] body) {
        Collection<EventRegistryEvent> events;
        try {
            var pipeline = (InboundEventProcessingPipeline) channel.getInboundEventProcessingPipeline();
            events = pipeline.run(channel, new DefaultInboundEvent(
                    new String(body, StandardCharsets.UTF_8), visibleHeaders(headers)));
        } catch (RuntimeException exception) {
            return fail(400, "INVALID_EVENT", "Không đọc được nội dung sự kiện");
        }
        if (events.isEmpty()) {
            return fail(422, "EVENT_NOT_RECOGNIZED", "Không nhận ra sự kiện trong nội dung");
        }
        for (EventRegistryEvent event : events) {
            InboundEventResult invalid = validate(tenant, channel, (EventInstance) event.getEventObject());
            if (invalid != null) {
                return invalid;
            }
        }
        try (InboundDispatchContext context = InboundDispatchContext.open()) {
            events.forEach(eventRegistry::sendEventToConsumers);
            return result(context);
        }
    }

    private static InboundEventResult validate(TenantContext.TenantInfo tenant, RestInboundChannelModel channel,
                                               EventInstance instance) {
        if (!channel.getAllowedEventKeys().contains(instance.getEventKey())) {
            return fail(422, "EVENT_NOT_ALLOWED", "Kênh không nhận sự kiện " + instance.getEventKey());
        }
        if (!tenant.tenantId().equals(instance.getTenantId())) {
            return fail(422, "EVENT_NOT_ALLOWED", "Sự kiện không thuộc tổ chức hiện tại");
        }
        if (instance.getCorrelationParameterInstances().isEmpty()) {
            return fail(422, "INVALID_CORRELATION", "Thiếu trường tương quan");
        }
        for (EventPayloadInstance correlation : instance.getCorrelationParameterInstances()) {
            Object value = correlation.getValue();
            if (value == null || value.toString().isBlank()) {
                return fail(422, "INVALID_CORRELATION",
                        "Trường tương quan " + correlation.getDefinitionName() + " đang trống");
            }
        }
        return null;
    }
    // result(context): MATCHED/PARTIAL → 200; NO_MATCH → 409 NO_WAITING_INSTANCE;
    // FAILED with every errorCode CONCURRENT_UPDATE → 409 CONCURRENT_UPDATE, else 500 EVENT_PROCESSING_FAILED.
    // allowed(security, principal): userIds contains principalId, or roles ∩ groupIds non-empty.
    // visibleHeaders: case-insensitive TreeMap without HIDDEN_HEADERS, values as Object.
    // fail(status, code, message): new InboundEventResult(status, code, code, message, List.of(), List.of(), false).
}
```

`instance.getCorrelationParameterInstances()` only lists the correlation fields **present** in the event. A field defined but missing from the body is absent, not null. That is why the empty-list check exists. Add a "missing one of two correlation fields" case to the integration test and decide from its result whether also to compare against the event model's correlation fields (`gateway.publishedEventFields`). If a missing field silently passes, add that comparison.

- [ ] **Step 2: Controller**

```java
@RestController
public class ChannelWebhookController {

    private final InboundEventReceiver receiver;
    private final EventRegistryProperties properties;

    // constructor

    @PostMapping(path = "/platform-api/channel-definitions/key/{channelKey}/events",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<?>> receive(@PathVariable String channelKey,
                                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                  HttpServletRequest request) throws IOException {
        int max = properties.getMaxBodyBytes();
        if (request.getContentLengthLong() > max) {
            return ResponseEntity.status(413).body(ApiResponse.fail("EVENT_TOO_LARGE", "Nội dung vượt " + max + " byte"));
        }
        byte[] body = request.getInputStream().readNBytes(max + 1);
        if (body.length > max) {
            return ResponseEntity.status(413).body(ApiResponse.fail("EVENT_TOO_LARGE", "Nội dung vượt " + max + " byte"));
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames()).forEach(name -> headers.put(name, request.getHeader(name)));
        InboundEventResult result = receiver.receive(channelKey, headers, body, key);
        var response = ResponseEntity.status(result.httpStatus());
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return result.httpStatus() < 300
                ? response.body(ApiResponse.ok(result))
                : response.body(ApiResponse.fail(result.code(), result.message()));
    }
}
```

`ChannelWebhookControllerTest`: use `MockMvcBuilders.standaloneSetup` with a mocked receiver. Cover:
- `413` from Content-Length.
- `413` from an actual long body without Content-Length.
- A `409` result maps to status 409 with the code in the body.
- `DUPLICATE` sets `Idempotent-Replayed: true`.

- [ ] **Step 3: Security**

In `SecurityConfig`, add to the CSRF `ignoringRequestMatchers` a matcher for webhook calls carrying a bearer token. A bearer token cannot be forged cross-site, while a cookie can, so cookie-authenticated browser posts still need CSRF:

```java
                        .ignoringRequestMatchers(request -> "POST".equalsIgnoreCase(request.getMethod())
                                && request.getRequestURI().startsWith("/platform-api/channel-definitions/")
                                && StringUtils.startsWithIgnoreCase(request.getHeader(HttpHeaders.AUTHORIZATION), "Bearer "))
```

`X-Workflow-Token` calls are already exempt by the existing matcher. Do not add `permitAll`.

If the service token filter rejects the path because of `allowed_paths`, document in the spec that a client must list `/platform-api/channel-definitions/**`. Do not widen defaults in code.

- [ ] **Step 4: Receiver integration test** (Testcontainers; flag on; `TenantContext` and `CurrentPrincipalContext` set by hand as principal `erp-client`)

Setup through the real services:
- Publish event `paymentReceived` (`orderId` correlation, `amount` double).
- Publish channel `payWebhook` (`FIXED`, `userIds=[erp-client]`).
- Deploy with `RepositoryService` directly, because the guard blocks event BPMN until slice 5, a process with a receive event task correlated on `${orderId}` and `eventOutParameter amount → paidAmount`, followed by a user task.
- Start two instances, `A-1` and `A-2`.

Cases, each with a fresh UUID unless stated:
1. Event for `A-1` → `200 MATCHED`. `paidAmount` is set and A-1 moved on. A-2 still waits.
2. Same UUID again → `200 DUPLICATE`, `replayed`, no trigger.
3. Event for `NOPE` → `409 NO_WAITING_INSTANCE`, and the inbox row is gone, so the same UUID can be retried.
4. Body `{"orderId":""}` → `422 INVALID_CORRELATION`.
5. Body `not json` → `400 INVALID_EVENT`.
6. Principal `intruder` → `403`.
7. Channel `missing` → `404`.
8. Missing or invalid `Idempotency-Key` → `400`.
9. `Authorization` header in the input never shows up as a variable or a header field. Add a `header` field named `authorization` to a second event model and assert it stays null.
10. Another tenant's context with the same channel key → `404`. Isolation comes from the tenant in `find`.

- [ ] **Step 5: Run the backend suite**

Run: `mvn -o -pl workflow-engine,workflow-boot -am test -Djacoco.skip=true -Dspotbugs.skip=true`

Expected: BUILD SUCCESS.

---

### Task 13: "Kênh nhận" tab (web)

**Files:**
- Modify: `src/features/event-registry/api.ts` (channel types and calls, `/workflow/management/event-registry/channels`)
- Create: `src/features/event-registry/channelDraft.ts`
- Test: `src/features/event-registry/channelDraft.spec.ts`
- Create: `src/features/event-registry/RestChannelPanel.vue`
- Test: `src/features/event-registry/RestChannelPanel.spec.ts`
- Modify: `src/features/event-registry/EventRegistryView.vue` (second tab)

- [ ] **Step 1: Helpers, test first**

`channelDraft.ts`:
- `validateChannel(form)` mirrors `RestChannelRules`, same messages.
- `webhookUrl(origin: string, path: string)` joins with one `/`.
- `curlExample(url, sampleBody)` returns a `curl -X POST` with `-H 'Authorization: Bearer <token>'`, `-H 'X-Tenant-Code: <tenant>'`, `-H 'Idempotency-Key: <uuid>'`, `-H 'Content-Type: application/json'`, and `-d` with a body built from the selected event's fields: `""` for string, `0` for numbers, `false` for boolean, `{}` for json.

- [ ] **Step 2: Panel**

Same list-plus-detail layout. Form fields:
- Mã kênh, Tên, Mô tả.
- "Nhận diện sự kiện": select Cố định / Trường JSON / JSON Pointer, plus a value input. With Cố định, the value is a select of published events.
- "Sự kiện được nhận": multi-select of published events, hidden for Cố định.
- "Người được gửi": tags input of principal ids.
- "Vai trò được gửi": tags input of role codes.

Below the form: the webhook URL (copy button) and the `curl` example. Buttons as in Task 8. The spec covers: switching to Cố định hides the multi-select, a channel with no user and no group shows the validation message, and the curl example contains `Idempotency-Key`.

- [ ] **Step 3: Checks**

Same commands as Task 8 Step 6. Expected: PASS.

---

### Task 14: End-to-end check on the local stack

Requires the local DB (V40 already applied by slices 1–2) and `WORKFLOW_EVENT_REGISTRY_ENABLED=true`, `VITE_EVENTS_ENABLED=true`.

- [ ] **Step 1:** In "Sự kiện", create `paymentReceived` with `orderId` as correlation (string) and `amount` (double), then publish. Expected: "Đã phát hành v1". Publish again: there is no v2.
- [ ] **Step 2:** Remove `amount`. Expected: the incompatibility list shows "Xóa trường amount", and "Phát hành" is disabled. Restore it.
- [ ] **Step 3:** In "Kênh nhận", create `payWebhook` (Cố định → `paymentReceived`), add your own principal id, then publish. Expected: the webhook URL is shown.
- [ ] **Step 4:** Post with the curl example and a real token for an instance that is not waiting. Expected: `409 NO_WAITING_INSTANCE`. Event waits cannot be designed until slice 5, so a full `MATCHED` is covered by Task 12's integration test only.
- [ ] **Step 5:** Try to delete `paymentReceived`. Expected: "Kênh payWebhook đang dùng sự kiện này".

---

### Task 15: Documentation and EIM

- [ ] **Step 1:** In `docs/superpowers/specs/2026-11-03-eim-configuration-checklist.md`:
  - In the node table, add `| 5 | WF_EVENT | WF_ROOT | Sự kiện | /events |` after `WF_SERVICE`, and renumber the following `ord` values.
  - In the role matrix, add `| WF_EVENT | ✔ | ✔ | | |`.
- [ ] **Step 2:** In the D1 spec, add "Hướng dẫn tích hợp webhook". Cover:
  - The URL.
  - The two authentication options (Keycloak service account recommended; `X-Workflow-Token` with `allowed_paths` covering `/platform-api/channel-definitions/**`).
  - `Idempotency-Key` as a UUID: reuse it only to retry the same event, and use a new key after `PARTIAL`.
  - The response code table.
  - Retry with backoff on `409 NO_WAITING_INSTANCE`, `409 CONCURRENT_UPDATE` and `503`.
- [ ] **Step 3:** Update «Lát triển khai → Trạng thái» with slices 3–4: what passed and what is left manual.

---

### Task 16: Full verification

- [ ] **Step 1: Backend**

Run: `mvn -o verify -Dcheckstyle.skip=true -Djacoco.haltOnFailure=false`, then `mvn -o -pl workflow-engine,workflow-boot,platform-migrations checkstyle:check@verify-java-conventions`.

Expected: BUILD SUCCESS. `workflow-engine` and `workflow-boot` meet their own coverage thresholds (no `Rule violated` line for them). SpotBugs reports 0. Checkstyle reports 0 for the changed modules. The existing auth-engine Checkstyle and service-registry JaCoCo failures stay as they are and are reported, not fixed here.

- [ ] **Step 2: Web**

Run: `npm run type-check && npx vitest --run`, plus lint on changed paths.

Expected: PASS.

- [ ] **Step 3: Report the changed files.** Do not commit.
