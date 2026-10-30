# Service Call Node Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Service Task maps expressions and output variable names onto parameters declared by the published operation, the way Flowable Work does.

**Architecture:** The catalog stores input and output parameters on the draft and copies them into the publish snapshot. `service-registry` places already coerced values and reads top-level JSON fields. `workflow-engine` evaluates Flowable expressions, coerces types, and writes process variables. The web panel edits the draft contract and binds the published contract.

**Tech Stack:** Java 25, Spring Boot, JUnit 5, Jackson, Flowable `DefaultExpressionManager`, Flyway, PostgreSQL, Vue 3, Vitest, Element Plus.

## Global Constraints

- Input parameter fields are `name`, `type`, `required`, `location`.
- Types are `STRING`, `INTEGER`, `LONG`, `DOUBLE`, `BOOLEAN`, `JSON`.
- Locations are `PATH`, `QUERY`, `HEADER`, `BODY`.
- `name` matches `^[A-Za-z_][A-Za-z0-9_]{0,127}$`, unique inside its own list, case-sensitive. An input name may equal an output name.
- `PATH` is always `required`.
- `jsonField` is one top-level JSON key, length 1 to 256, with no control characters. A `.` is part of the key.
- Output parameter fields are `name`, `type`, `jsonField`.
- Migration file is `V33__add_service_operation_parameters.sql` with `input_parameters_json` and `output_parameters_json`, both `TEXT NOT NULL DEFAULT '[]'`.
- BPMN keeps `serviceKey`, `operationKey`, `inputMapping`, `outputMapping` on `workflow:serviceCall`. Delegate stays `${serviceRegistryDelegate}`.
- Input mapping shape is `[{"name":"customerName","expression":"${customerName}"}]`. Output mapping shape is `[{"name":"id","variable":"leadId"}]`.
- A trimmed blank expression is no value. Java `null` is no value. An empty string is a present value.
- A cell containing `${` or `#{` is passed whole to `ExpressionManager.createExpression` and `getValue(execution)`. Any other cell is a literal. `customerName` is the string `customerName`.
- Missing required input, a thrown expression, or bad input coercion throws `ServiceParameterException` before `invoke`.
- `ServiceParameterException` is not a `BpmnError`. The delegate does not catch it. Its message contains `serviceKey`, `operationKey`, and the reason. A parameter failure also contains the parameter name. The message does not contain the resolved value.
- `SERVICE_NOT_PUBLISHED`, the operation error code, `HTTP_ERROR`, and `SERVICE_UNAVAILABLE` stay `BpmnError`.
- `InvalidServiceRequestException` is thrown before the HTTP client runs when the URL still contains `${` or the body template is not a JSON object. The adapter turns it into `ServiceParameterException`.
- Optional absent values are omitted. An absent `BODY` parameter removes that field from the template object.
- `PATH` replaces `${name}` in the URL. A `PATH` name that is not in the URL is ignored.
- `PATH`, `QUERY`, and `HEADER` are strings. `JSON` in those locations is compact JSON. `BODY` keeps JSON types.
- Response `2xx` is parsed with Jackson. Only the top-level `jsonField` is read. A missing field or JSON `null` is absent from the result map.
- Output coercion happens after `invoke` returns and before any `setVariable`. A coercion failure throws `ServiceParameterException` and does not write variables from that call.
- The node reads `publishedMethod`, `publishedUrl`, `publishedBpmnErrorCode`, `publishedInputParameters`, and `publishedOutputParameters`.
- Saving a diagram with an empty required expression warns and still writes the BPMN.
- An invalid output variable name warns and keeps the previous valid name, or the parameter name.
- The «Loại bước» control is hidden on a Service Task. `isTask` stays unchanged.
- No OAuth2, retry, OpenAPI, multipart, version pinning, nested JSON path, async service task, incident screen, or browser end-to-end test.

Commands for Java tasks run from `workflow-agent-service`. Web commands run from `workflow-agent-web`.

---

### Task 1: Parameter contract rules

**Files:**
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ParameterType.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/InputParameter.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/OutputParameter.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ParameterContracts.java`
- Test: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/ParameterContractTest.java`

**Interfaces:**
- Consumes: `ParameterLocation`
- Produces: `ParameterContracts.check(List<InputParameter> inputs, List<OutputParameter> outputs)` throws `IllegalArgumentException`. Null lists are treated as empty. Valid lists are accepted, including an input and an output that share a name.

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ParameterContractTest {

    @Test
    void acceptsSharedNameAndRejectsDuplicatePathAndJsonField() {
        assertDoesNotThrow(() -> ParameterContracts.check(
                List.of(new InputParameter("customerId", ParameterType.STRING, true, ParameterLocation.PATH)),
                List.of(new OutputParameter("customerId", ParameterType.STRING, "customer.id"))));

        assertThrows(IllegalArgumentException.class, () -> ParameterContracts.check(
                List.of(
                        new InputParameter("customerId", ParameterType.STRING, true, ParameterLocation.PATH),
                        new InputParameter("customerId", ParameterType.STRING, true, ParameterLocation.QUERY)),
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> ParameterContracts.check(
                List.of(new InputParameter("order id", ParameterType.STRING, true, ParameterLocation.QUERY)),
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> ParameterContracts.check(
                List.of(new InputParameter("orderId", ParameterType.STRING, false, ParameterLocation.PATH)),
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> ParameterContracts.check(
                List.of(),
                List.of(new OutputParameter("id", ParameterType.STRING, "bad\nfield"))));
        assertThrows(IllegalArgumentException.class, () -> ParameterContracts.check(
                List.of(),
                List.of(new OutputParameter("id", ParameterType.STRING, ""))));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl service-registry test -Dtest=ParameterContractTest`

Expected: FAIL because `ParameterContracts` does not exist.

- [ ] **Step 3: Write the minimal implementation**

`ParameterType.java`:

```java
package io.github.illuseahashmap.serviceregistry.domain;

public enum ParameterType {
    STRING, INTEGER, LONG, DOUBLE, BOOLEAN, JSON
}
```

`InputParameter.java`:

```java
package io.github.illuseahashmap.serviceregistry.domain;

public record InputParameter(String name, ParameterType type, boolean required, ParameterLocation location) {
}
```

`OutputParameter.java`:

```java
package io.github.illuseahashmap.serviceregistry.domain;

public record OutputParameter(String name, ParameterType type, String jsonField) {
}
```

`ParameterContracts.java`:

```java
package io.github.illuseahashmap.serviceregistry.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ParameterContracts {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    private ParameterContracts() {
    }

    public static void check(List<InputParameter> inputs, List<OutputParameter> outputs) {
        checkInputs(inputs == null ? List.of() : inputs);
        checkOutputs(outputs == null ? List.of() : outputs);
    }

    private static void checkInputs(List<InputParameter> inputs) {
        Set<String> names = new HashSet<>();
        for (InputParameter input : inputs) {
            if (input == null || input.type() == null || input.location() == null || !NAME.matcher(text(input.name())).matches()) {
                throw new IllegalArgumentException("Invalid input parameter");
            }
            if (!names.add(input.name())) {
                throw new IllegalArgumentException("Duplicate input parameter");
            }
            if (input.location() == ParameterLocation.PATH && !input.required()) {
                throw new IllegalArgumentException("PATH parameter must be required");
            }
        }
    }

    private static void checkOutputs(List<OutputParameter> outputs) {
        Set<String> names = new HashSet<>();
        for (OutputParameter output : outputs) {
            if (output == null || output.type() == null || !NAME.matcher(text(output.name())).matches()) {
                throw new IllegalArgumentException("Invalid output parameter");
            }
            if (!names.add(output.name())) {
                throw new IllegalArgumentException("Duplicate output parameter");
            }
            String field = output.jsonField();
            if (field == null || field.isBlank() || field.length() > 256 || hasControl(field)) {
                throw new IllegalArgumentException("Invalid JSON field");
            }
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl service-registry test -Dtest=ParameterContractTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ParameterType.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/InputParameter.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/OutputParameter.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ParameterContracts.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/ParameterContractTest.java
git commit -m "feat: validate service operation parameter contracts"
```

---

### Task 2: Draft and published parameters in the catalog

**Files:**
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/PublishedOperation.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/OperationView.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/InMemoryServiceCatalog.java`
- Modify: every current `new PublishedOperation(...)` and `new OperationView(...)` so the project compiles
- Test: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCatalogTest.java`

**Interfaces:**
- Consumes: `ParameterContracts.check`, `InputParameter`, `OutputParameter`
- Produces: `PublishedOperation` gains `List<InputParameter> inputs` and `List<OutputParameter> outputs`. Null lists become empty copies. `OperationView` gains `publishedMethod`, `publishedBpmnErrorCode`, `inputParameters`, `outputParameters`, `publishedInputParameters`, `publishedOutputParameters` after `publishedUrl`. `saveOperation` and `saveDraft` reject an invalid contract without replacing the previous draft.

- [ ] **Step 1: Write the failing test**

Add this test to `ServiceCatalogTest`:

```java
@Test
void saveOperationReplacesParametersAndKeepsPublishedSnapshot() {
    var catalog = new InMemoryServiceCatalog();
    catalog.saveService("crm", "CRM");
    catalog.saveOperation("crm", operation("https://old.example", List.of(
            new InputParameter("orderId", ParameterType.STRING, true, ParameterLocation.PATH)), List.of(
            new OutputParameter("id", ParameterType.LONG, "id"))));
    catalog.publish("crm");

    assertThrows(IllegalArgumentException.class, () -> catalog.saveOperation("crm", operation(
            "https://old.example",
            List.of(new InputParameter("orderId", ParameterType.STRING, false, ParameterLocation.PATH)),
            List.of())));
    assertEquals(ParameterLocation.PATH, catalog.find("crm").orElseThrow().operations().getFirst()
            .inputParameters().getFirst().location());

    catalog.saveOperation("crm", operation("https://new.example", List.of(
            new InputParameter("name", ParameterType.STRING, false, ParameterLocation.QUERY)), List.of()));
    PublishedOperation published = catalog.published("crm", "createLead").orElseThrow();
    assertEquals("https://old.example", published.urlTemplate());
    assertEquals("orderId", published.inputs().getFirst().name());
    OperationView view = catalog.find("crm").orElseThrow().operations().getFirst();
    assertEquals("name", view.inputParameters().getFirst().name());
    assertEquals("orderId", view.publishedInputParameters().getFirst().name());
    assertEquals(HttpMethod.POST, view.publishedMethod());
    assertEquals("HTTP_ERROR", view.publishedBpmnErrorCode());
}
```

Change the existing helper to:

```java
private static PublishedOperation operation(String url) {
    return operation(url, List.of(), List.of());
}

private static PublishedOperation operation(String url, List<InputParameter> inputs, List<OutputParameter> outputs) {
    return new PublishedOperation(
            "createLead", HttpMethod.POST, url,
            AuthType.NONE, null, null, null, 1000, List.of(), "HTTP_ERROR",
            inputs, outputs);
}
```

Add the new imports. `assertThrows` and `assertEquals` come from `org.junit.jupiter.api.Assertions`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl service-registry test -Dtest=ServiceCatalogTest`

Expected: FAIL because `PublishedOperation` does not yet take parameter lists.

- [ ] **Step 3: Write the minimal implementation**

Replace `PublishedOperation` with:

```java
package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

public record PublishedOperation(
        String operationKey,
        HttpMethod method,
        String urlTemplate,
        AuthType authType,
        String apiKeyHeader,
        String secret,
        String bodyTemplate,
        int timeoutMillis,
        List<String> errorStatusCodes,
        String bpmnErrorCode,
        List<InputParameter> inputs,
        List<OutputParameter> outputs
) {
    public PublishedOperation {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
    }
}
```

Replace `OperationView` with:

```java
package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

public record OperationView(
        String operationKey,
        HttpMethod method,
        String urlTemplate,
        AuthType authType,
        String apiKeyHeader,
        boolean secretPresent,
        String bodyTemplate,
        int timeoutMillis,
        List<String> errorStatusCodes,
        String bpmnErrorCode,
        String publishedUrl,
        HttpMethod publishedMethod,
        String publishedBpmnErrorCode,
        List<InputParameter> inputParameters,
        List<OutputParameter> outputParameters,
        List<InputParameter> publishedInputParameters,
        List<OutputParameter> publishedOutputParameters
) {
}
```

In `InMemoryServiceCatalog.saveOperation`, call `ParameterContracts.check(operation.inputs(), operation.outputs())` before reading the draft. Copy `operation.inputs()` and `operation.outputs()` into the `PublishedOperation` it stores. Keep the existing null-secret preservation.

In `saveDraft`, call `ParameterContracts.check` for every operation before `drafts.put`. A failure leaves the previous draft in place.

Replace `find` so published metadata comes from the published operation object:

```java
Map<String, PublishedOperation> published = new HashMap<>();
for (PublishedOperation operation : publications.getOrDefault(serviceKey, List.of())) {
    published.put(operation.operationKey(), operation);
}
List<OperationView> operations = draft.operations().stream()
        .map(operation -> {
            PublishedOperation snapshot = published.get(operation.operationKey());
            return new OperationView(
                    operation.operationKey(),
                    operation.method(),
                    operation.urlTemplate(),
                    operation.authType(),
                    operation.apiKeyHeader(),
                    operation.secret() != null,
                    operation.bodyTemplate(),
                    operation.timeoutMillis(),
                    operation.errorStatusCodes(),
                    operation.bpmnErrorCode(),
                    snapshot == null ? null : snapshot.urlTemplate(),
                    snapshot == null ? null : snapshot.method(),
                    snapshot == null ? null : snapshot.bpmnErrorCode(),
                    operation.inputs(),
                    operation.outputs(),
                    snapshot == null ? List.of() : snapshot.inputs(),
                    snapshot == null ? List.of() : snapshot.outputs());
        })
        .toList();
```

Update every other `new PublishedOperation(...)` by adding `List.of(), List.of()` as the last two arguments. Update every other `new OperationView(...)` by adding `null, null, List.of(), List.of(), List.of(), List.of()` after the published URL. The files are:

- `ServiceCallerTest`
- `CallClassifierTest`
- `RequestFactoryTest`
- `JdbcServiceCatalogTest`
- `JdbcServiceCatalog.toOperation` and `JdbcServiceCatalog.find`
- `ServiceCatalogController.saveOperation`

Those call sites pass empty parameter lists until Tasks 3, 4, 5, and 6 fill them in. This task only has to compile.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl service-registry test -Dtest=ServiceCatalogTest,ParameterContractTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/PublishedOperation.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/OperationView.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/InMemoryServiceCatalog.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/infrastructure/JdbcServiceCatalog.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/interfaces/ServiceCatalogController.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCatalogTest.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCallerTest.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/CallClassifierTest.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/RequestFactoryTest.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/infrastructure/JdbcServiceCatalogTest.java
git commit -m "feat: keep operation parameters in the catalog snapshot"
```

---

### Task 3: Persist parameters

**Files:**
- Create: `workflow-agent-service/platform-migrations/src/main/resources/db/migration/platform/V33__add_service_operation_parameters.sql`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/infrastructure/JdbcServiceCatalog.java`
- Modify: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/infrastructure/JdbcServiceCatalogTest.java`
- Modify: `workflow-agent-service/workflow-boot/src/test/java/io/github/illuseahashmap/workflow/integration/PlatformMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: `PublishedOperation.inputs()`, `PublishedOperation.outputs()`, `ParameterContracts.check`
- Produces: draft columns and snapshot JSON fields `inputParameters` and `outputParameters`. A snapshot written before those fields reads as empty lists. `find` fills the published fields from the snapshot.

- [ ] **Step 1: Write the failing test**

Add to `JdbcServiceCatalogTest`:

```java
@Test
void parameterChangeStaysInDraftUntilPublishAndOldSnapshotHasNoParameters() throws Exception {
    applyMigration();
    var catalog = new JdbcServiceCatalog(dataSource(), new ServiceSecretCipher(KEY));
    catalog.saveDraft(new ServiceDraft("legacy", "Legacy", List.of(operation("https://old.example", null))));
    catalog.publish("legacy");
    try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
         Statement statement = connection.createStatement()) {
        statement.executeUpdate("""
                UPDATE service_publication
                SET snapshot_json = replace(snapshot_json, ',"inputParameters":[]', '')
                WHERE service_key = 'legacy'
                """);
        statement.executeUpdate("""
                UPDATE service_publication
                SET snapshot_json = replace(snapshot_json, ',"outputParameters":[]', '')
                WHERE service_key = 'legacy'
                """);
    }
    assertEquals(List.of(), catalog.published("legacy", "createLead").orElseThrow().inputs());

    catalog.saveService("crm", "CRM");
    catalog.saveOperation("crm", operation("https://crm.example/leads", "s3cret"));
    catalog.publish("crm");
    assertThrows(IllegalArgumentException.class, () -> catalog.saveOperation("crm", new PublishedOperation(
            "createLead", HttpMethod.POST, "https://crm.example/leads",
            AuthType.BEARER, null, null, null, 1000, List.of("409"), "LEAD_CONFLICT",
            List.of(new InputParameter("orderId", ParameterType.STRING, false, ParameterLocation.PATH)),
            List.of())));
    assertEquals("orderId", catalog.published("crm", "createLead").orElseThrow().inputs().getFirst().name());
    catalog.saveOperation("crm", new PublishedOperation(
            "createLead", HttpMethod.POST, "https://changed.example/leads",
            AuthType.BEARER, null, null, null, 1000, List.of("409"), "LEAD_CONFLICT",
            List.of(new InputParameter("name", ParameterType.STRING, false, ParameterLocation.QUERY)),
            List.of()));
    assertEquals("orderId", catalog.published("crm", "createLead").orElseThrow().inputs().getFirst().name());
    assertEquals("name", catalog.find("crm").orElseThrow().operations().getFirst().inputParameters().getFirst().name());
}
```

Change `operation` in that test to accept parameters and pass `List.of(new InputParameter("orderId", ParameterType.STRING, true, ParameterLocation.PATH)), List.of()` from the new test's valid save. The existing test helper keeps `List.of(), List.of()`.

Extend `applyMigration` so it executes `V32__add_service_registry.sql` and then `V33__add_service_operation_parameters.sql`.

In `PlatformMigrationIntegrationTest.migratesCleanDatabaseThroughLatestPlatformVersion`, after the service table assertions, add:

```java
assertThat(columnExists("service_operation_draft", "input_parameters_json")).isTrue();
assertThat(columnExists("service_operation_draft", "output_parameters_json")).isTrue();
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl service-registry test -Dtest=JdbcServiceCatalogTest`

Expected: FAIL because `V33__add_service_operation_parameters.sql` does not exist.

- [ ] **Step 3: Write the minimal implementation**

`V33__add_service_operation_parameters.sql`:

```sql
ALTER TABLE service_operation_draft
    ADD COLUMN input_parameters_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN output_parameters_json TEXT NOT NULL DEFAULT '[]';
```

Add `inputParameters` and `outputParameters` to `StoredOperation`. In `toOperation`, pass empty lists when the stored lists are null.

Call `ParameterContracts.check(operation.inputs(), operation.outputs())` at the start of `saveOperation` and for each operation in `saveDraft`, before any write.

Add the two columns to both `INSERT` statements and both `ON CONFLICT` updates. Bind `writeJson(operation.inputs())` and `writeJson(operation.outputs())`. `writeJson` uses the existing `ObjectMapper` and throws `IllegalStateException` when Jackson fails.

Select the two columns in `readDraft`. Parse blank JSON as `List.of()`. Parse with:

```java
private static final TypeReference<List<InputParameter>> INPUTS = new TypeReference<>() {
};
private static final TypeReference<List<OutputParameter>> OUTPUTS = new TypeReference<>() {
};
```

Replace the published-URL-only map in `find` with a map of `StoredOperation`. Fill `publishedMethod`, `publishedBpmnErrorCode`, and the published parameter lists from that row. Draft lists come from the draft row. A missing snapshot leaves published fields null or empty, matching `InMemoryServiceCatalog.find`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl service-registry test -Dtest=JdbcServiceCatalogTest`

Expected: PASS. Docker is required. `disabledWithoutDocker` skips the test when Docker is absent; do not treat a skip as success when Docker is available.

Run: `mvn -pl workflow-boot -am test -Dtest=PlatformMigrationIntegrationTest`

Expected: PASS when Docker is available.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/platform-migrations/src/main/resources/db/migration/platform/V33__add_service_operation_parameters.sql workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/infrastructure/JdbcServiceCatalog.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/infrastructure/JdbcServiceCatalogTest.java workflow-agent-service/workflow-boot/src/test/java/io/github/illuseahashmap/workflow/integration/PlatformMigrationIntegrationTest.java
git commit -m "feat: store operation parameters in the published snapshot"
```

---

### Task 4: Catalog HTTP contract

**Files:**
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/interfaces/ServiceCatalogController.java`
- Test: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/interfaces/ServiceCatalogControllerTest.java`

**Interfaces:**
- Consumes: `InputParameter`, `OutputParameter`, `ParameterType`, `ParameterLocation`, `PublishedOperation` parameter lists
- Produces: `PUT /services/{serviceKey}/operations/{operationKey}` accepts `inputParameters` and `outputParameters`. Null lists mean empty. Invalid contracts return HTTP 400 and do not change the draft. `GET /services` and `GET /services/{serviceKey}` return draft and published parameter fields.

- [ ] **Step 1: Write the failing test**

Add to `ServiceCatalogControllerTest`:

```java
@Test
void returnsDraftAndPublishedParametersAndRejectsPathThatIsOptional() throws Exception {
    mvc.perform(post("/services")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"serviceKey\":\"crm\",\"name\":\"CRM\"}"))
            .andExpect(status().isOk());
    mvc.perform(put("/services/crm/operations/createLead")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "method": "POST",
                              "url": "https://crm.example/orders/${orderId}",
                              "authType": "NONE",
                              "bodyTemplate": "{}",
                              "timeoutMillis": 1000,
                              "errorStatusCodes": ["409"],
                              "bpmnErrorCode": "HTTP_ERROR",
                              "inputParameters": [
                                {"name": "orderId", "type": "STRING", "required": true, "location": "PATH"}
                              ],
                              "outputParameters": [
                                {"name": "id", "type": "LONG", "jsonField": "id"}
                              ]
                            }
                            """))
            .andExpect(status().isOk());
    mvc.perform(post("/services/crm/publish")).andExpect(status().isOk());
    mvc.perform(put("/services/crm/operations/createLead")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "method": "POST",
                              "url": "https://crm.example/orders/${orderId}",
                              "authType": "NONE",
                              "timeoutMillis": 1000,
                              "bpmnErrorCode": "HTTP_ERROR",
                              "inputParameters": [
                                {"name": "orderId", "type": "STRING", "required": false, "location": "PATH"}
                              ]
                            }
                            """))
            .andExpect(status().isBadRequest());

    mvc.perform(get("/services/crm"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.operations[0].inputParameters[0].name").value("orderId"))
            .andExpect(jsonPath("$.data.operations[0].publishedInputParameters[0].required").value(true))
            .andExpect(jsonPath("$.data.operations[0].publishedMethod").value("POST"))
            .andExpect(jsonPath("$.data.operations[0].publishedBpmnErrorCode").value("HTTP_ERROR"));
    mvc.perform(get("/services"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].operations[0].publishedOutputParameters[0].jsonField").value("id"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl service-registry test -Dtest=ServiceCatalogControllerTest`

Expected: FAIL because the payload fields are ignored and the optional `PATH` save returns 200.

- [ ] **Step 3: Write the minimal implementation**

Add request records:

```java
public record InputParameterRequest(String name, String type, boolean required, String location) {
}

public record OutputParameterRequest(String name, String type, String jsonField) {
}
```

Add the two lists to `SaveOperationRequest`. Map them with `ParameterType.valueOf` and `ParameterLocation.valueOf`. A null list becomes `List.of()`. Pass the lists to `PublishedOperation`.

Wrap the body of `saveOperation` in:

```java
try {
    catalog.saveOperation(serviceKey, operation);
} catch (IllegalArgumentException exception) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
}
```

`ParameterContracts.check` inside the catalog produces the `IllegalArgumentException`. `valueOf` on an unknown type or location does too.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl service-registry test -Dtest=ServiceCatalogControllerTest,ServiceCatalogTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/interfaces/ServiceCatalogController.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/interfaces/ServiceCatalogControllerTest.java
git commit -m "feat: expose published operation parameters over HTTP"
```

---

### Task 5: Place resolved values on the request

**Files:**
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/InvalidServiceRequestException.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/RequestFactory.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCaller.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/application/PublishedServiceInvoker.java`
- Test: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/RequestFactoryTest.java`
- Test: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCallerTest.java`

**Interfaces:**
- Consumes: `PublishedOperation.inputs()`, coerced values in a `Map<String, Object>`
- Produces: `RequestFactory.create(PublishedOperation operation, Map<String, Object> resolvedInputs)`. `InvalidServiceRequestException` for a leftover `${` in the URL or a non-object body template. `ServiceCaller.call(PublishedOperation operation, Map<String, Object> resolvedInputs, List<OutputBinding> outputs)`. The invoker builds that map from its existing `InputBinding` list with `variables.get(name)` so the current gateway still compiles. `InputBinding` stays until Task 8.

- [ ] **Step 1: Write the failing test**

Replace `RequestFactoryTest` with tests that call `RequestFactory.create(operation, resolved)` and put parameters on the operation:

```java
@Test
void placesResolvedValuesAndKeepsJsonTypes() {
    var operation = new PublishedOperation(
            "createLead", HttpMethod.POST, "https://crm.example/orders/${orderId}",
            AuthType.BEARER, null, "s3cret",
            "{\"name\":null,\"keep\":1}", 5000, List.of("409"), "HTTP_ERROR",
            List.of(
                    new InputParameter("orderId", ParameterType.STRING, true, ParameterLocation.PATH),
                    new InputParameter("q", ParameterType.STRING, false, ParameterLocation.QUERY),
                    new InputParameter("trace", ParameterType.STRING, false, ParameterLocation.HEADER),
                    new InputParameter("customerName", ParameterType.STRING, true, ParameterLocation.BODY),
                    new InputParameter("count", ParameterType.INTEGER, true, ParameterLocation.BODY),
                    new InputParameter("payload", ParameterType.JSON, true, ParameterLocation.BODY),
                    new InputParameter("name", ParameterType.STRING, false, ParameterLocation.BODY),
                    new InputParameter("meta", ParameterType.JSON, true, ParameterLocation.HEADER),
                    new InputParameter("unused", ParameterType.STRING, false, ParameterLocation.PATH)),
            List.of());
    var request = RequestFactory.create(operation, Map.of(
            "orderId", "9",
            "q", "open",
            "trace", "abc",
            "customerName", "Ann",
            "count", 2,
            "payload", Map.of("sku", "A"),
            "meta", Map.of("a", 1)));

    assertEquals("https://crm.example/orders/9?q=open", request.url());
    assertEquals("Bearer s3cret", request.headers().get("Authorization"));
    assertEquals("abc", request.headers().get("trace"));
    assertEquals("{\"a\":1}", request.headers().get("meta"));
    assertTrue(request.body().contains("\"customerName\":\"Ann\""));
    assertTrue(request.body().contains("\"count\":2"));
    assertTrue(request.body().contains("\"payload\":{\"sku\":\"A\"}"));
    assertTrue(request.body().contains("\"keep\":1"));
    assertFalse(request.body().contains("\"name\""));
}

@Test
void omitsAbsentOptionalValues() {
    var operation = new PublishedOperation(
            "createLead", HttpMethod.GET, "https://crm.example/leads",
            AuthType.NONE, null, null, null, 1000, List.of(), "HTTP_ERROR",
            List.of(new InputParameter("q", ParameterType.STRING, false, ParameterLocation.QUERY)),
            List.of());
    var request = RequestFactory.create(operation, Map.of());
    assertEquals("https://crm.example/leads", request.url());
    assertFalse(request.headers().containsKey("q"));
}

@Test
void rejectsLeftoverPlaceholderAndNonObjectBody() {
    var placeholder = new PublishedOperation(
            "createLead", HttpMethod.GET, "https://crm.example/${missing}",
            AuthType.NONE, null, null, null, 1000, List.of(), "HTTP_ERROR",
            List.of(), List.of());
    assertThrows(InvalidServiceRequestException.class, () -> RequestFactory.create(placeholder, Map.of()));

    var body = new PublishedOperation(
            "createLead", HttpMethod.POST, "https://crm.example/leads",
            AuthType.NONE, null, null, "[]", 1000, List.of(), "HTTP_ERROR",
            List.of(new InputParameter("name", ParameterType.STRING, true, ParameterLocation.BODY)),
            List.of());
    assertThrows(InvalidServiceRequestException.class, () -> RequestFactory.create(body, Map.of("name", "Ann")));
}
```

Add this test to `RequestFactoryTest`:

```java
@Test
void placesApiKeyInNamedHeader() {
    var operation = new PublishedOperation(
            "createLead", HttpMethod.GET, "https://crm.example/leads",
            AuthType.API_KEY, "X-Api-Key", "s3cret",
            null, 1000, List.of(), "HTTP_ERROR",
            List.of(new InputParameter("trace", ParameterType.STRING, false, ParameterLocation.HEADER)),
            List.of());
    var request = RequestFactory.create(operation, Map.of("trace", "1"));
    assertEquals("s3cret", request.headers().get("X-Api-Key"));
    assertEquals("1", request.headers().get("trace"));
    assertFalse(request.headers().containsKey("Authorization"));
}
```

Add this test to `ServiceCallerTest`. `call` in this task still takes the output-binding list, so pass `List.of()`:

```java
@Test
void doesNotSendWhenTheUrlStillHasAPlaceholder() {
    var called = new java.util.concurrent.atomic.AtomicBoolean(false);
    HttpTransport transport = request -> {
        called.set(true);
        return new TransportResponse(200, "{}");
    };
    var caller = new ServiceCaller(transport);
    var operation = new PublishedOperation(
            "createLead", HttpMethod.GET, "https://crm.example/${missing}",
            AuthType.NONE, null, null, null, 1000, java.util.List.of(), "HTTP_ERROR",
            java.util.List.of(), java.util.List.of());
    org.junit.jupiter.api.Assertions.assertThrows(InvalidServiceRequestException.class,
            () -> caller.call(operation, java.util.Map.of(), java.util.List.of()));
    org.junit.jupiter.api.Assertions.assertFalse(called.get());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl service-registry test -Dtest=RequestFactoryTest,ServiceCallerTest`

Expected: FAIL because `create` still takes `InputBinding` and does not throw `InvalidServiceRequestException`.

- [ ] **Step 3: Write the minimal implementation**

```java
package io.github.illuseahashmap.serviceregistry.domain;

public class InvalidServiceRequestException extends RuntimeException {

    public InvalidServiceRequestException(String message) {
        super(message);
    }
}
```

`RequestFactory.create` walks `operation.inputs()`. A name missing from the map, or a null value, is absent. Present values are placed by location.

String form by type:

- `STRING`: the value is already a `String`
- `BOOLEAN`: `true` or `false`
- `INTEGER` and `LONG`: decimal digits with no fraction
- `DOUBLE`: `Double.toString`
- `JSON`: compact Jackson JSON, so a string is quoted and a map is an object

`PATH` replaces `${name}` with the URL path encoding already in `encodePath`. After every path replacement, a URL that still contains `${` throws `InvalidServiceRequestException` with message `URL still contains a placeholder`.

`QUERY` uses the existing `encodeQuery`. `HEADER` sets the parameter name. Apply auth after parameters, so the operation auth header wins.

`BODY` starts from `bodyTemplate`. Blank template means `{}`. Parse with Jackson. A root that is not an object throws `InvalidServiceRequestException` with message `body template is not a JSON object`. Set a present parameter with `objectMapper.valueToTree(value)` under the parameter name. Remove an absent parameter with `remove(name)`. Write the object back with compact Jackson. When `operation.inputs()` has no `BODY` parameter, leave `bodyTemplate` unchanged, including null.

Messages from this class do not include parameter values.

`ServiceCaller.call` takes `(PublishedOperation operation, Map<String, Object> resolvedInputs, List<OutputBinding> outputs)`. It calls `RequestFactory.create` before `transport.send`. An `InvalidServiceRequestException` propagates and the transport is not called.

`PublishedServiceInvoker.invoke` still accepts the old JSON arguments. It builds `Map<String, Object>` by putting `variables.get(input.name())` for each parsed `InputBinding`, then calls the new `ServiceCaller.call`. This keeps `ServiceRegistryGatewayAdapter` compiling until Task 8.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl service-registry test -Dtest=RequestFactoryTest,ServiceCallerTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/InvalidServiceRequestException.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/RequestFactory.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCaller.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/application/PublishedServiceInvoker.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/RequestFactoryTest.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCallerTest.java
git commit -m "feat: place coerced parameter values on service requests"
```

---

### Task 6: Read response fields with Jackson

**Files:**
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/CallClassifier.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCaller.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/application/PublishedServiceInvoker.java`
- Delete: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/OutputBinding.java`
- Test: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/CallClassifierTest.java`
- Test: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCallerTest.java`

**Interfaces:**
- Consumes: `PublishedOperation.outputs()`
- Produces: `CallClassifier.classify(PublishedOperation operation, int status, String responseBody)`. Success map keys are output parameter names. Missing and JSON-null fields are absent. Objects and arrays stay structured. HTTP error codes stay `LEAD_CONFLICT` or `HTTP_ERROR` as they do now. `ServiceCaller.call(PublishedOperation, Map<String, Object>)` no longer takes output bindings.

- [ ] **Step 1: Write the failing test**

```java
@Test
void readsTopLevelFieldsAndSkipsNulls() {
    var operation = new PublishedOperation(
            "createLead", HttpMethod.POST, "https://crm.example/leads",
            AuthType.NONE, null, null, null, 1000, List.of("409"), "LEAD_CONFLICT",
            List.of(),
            List.of(
                    new OutputParameter("id", ParameterType.STRING, "id"),
                    new OutputParameter("customer", ParameterType.JSON, "customer"),
                    new OutputParameter("missing", ParameterType.STRING, "missing"),
                    new OutputParameter("empty", ParameterType.STRING, "note")));
    var success = CallClassifier.classify(operation, 201, """
            {"id":"L1","customer":{"name":"Ann"},"items":[1],"note":null}
            """);
    assertFalse(success.failed());
    assertEquals("L1", success.variables().get("id"));
    assertEquals("Ann", ((Map<?, ?>) success.variables().get("customer")).get("name"));
    assertFalse(success.variables().containsKey("missing"));
    assertFalse(success.variables().containsKey("empty"));

    assertEquals("LEAD_CONFLICT", CallClassifier.classify(operation, 409, "{}").bpmnErrorCode());
    assertEquals("HTTP_ERROR", CallClassifier.classify(operation, 404, "{}").bpmnErrorCode());
}
```

Update `ServiceCallerTest.classifiesTransportResponseAndSendsOperationMethod` so the operation carries `new OutputParameter("id", ParameterType.STRING, "id")` and the assertion reads `outcome.variables().get("id")`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl service-registry test -Dtest=CallClassifierTest`

Expected: FAIL because `classify` still takes `OutputBinding` and uses a scalar regular expression.

- [ ] **Step 3: Write the minimal implementation**

`classify` reads outputs from `operation.outputs()`. Status handling stays as it is: configured match uses `operation.bpmnErrorCode()` or `HTTP_ERROR`; any other non-2xx uses `HTTP_ERROR`.

For `2xx`, parse a blank body as no fields. Parse with Jackson. A non-object body yields an empty success map. For each output, read `body.get(output.jsonField())` only. A missing node or a null node is skipped. Otherwise `objectMapper.convertValue(node, Object.class)` is stored under `output.name()`.

`ServiceCaller.call(PublishedOperation operation, Map<String, Object> resolvedInputs)` calls `CallClassifier.classify(operation, response.status(), response.body())`.

`PublishedServiceInvoker` stops parsing output JSON. It still parses input JSON into `InputBinding` values for one more task.

Delete `OutputBinding.java` after nothing references it.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl service-registry test -Dtest=CallClassifierTest,ServiceCallerTest,RequestFactoryTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/CallClassifier.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCaller.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/application/PublishedServiceInvoker.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/OutputBinding.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/CallClassifierTest.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCallerTest.java
git commit -m "feat: read service response fields with Jackson"
```

---

### Task 7: Coerce parameter values

**Files:**
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/ServiceParameterException.java`
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/ServiceParameterType.java`
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/ParameterCoercion.java`
- Test: `workflow-agent-service/workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/application/ParameterCoercionTest.java`

**Interfaces:**
- Consumes: nothing from the registry
- Produces: `ParameterCoercion.coerce(ServiceParameterType type, Object value)`. JSON `null` returns Java `null`. Every failure throws `IllegalArgumentException` whose message is `cannot coerce to TYPE` and does not contain the value.

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.workflow.process.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParameterCoercionTest {

    @Test
    void coercesScalarsAndRejectsMismatches() {
        assertEquals("Ann", ParameterCoercion.coerce(ServiceParameterType.STRING, "Ann"));
        assertEquals("1", ParameterCoercion.coerce(ServiceParameterType.STRING, 1.0d));
        assertEquals("1.5", ParameterCoercion.coerce(ServiceParameterType.STRING, 1.5d));
        assertEquals("true", ParameterCoercion.coerce(ServiceParameterType.STRING, true));
        assertEquals(1, ParameterCoercion.coerce(ServiceParameterType.INTEGER, 1.0d));
        assertEquals(1, ParameterCoercion.coerce(ServiceParameterType.INTEGER, "1"));
        assertEquals(4L, ParameterCoercion.coerce(ServiceParameterType.LONG, "4"));
        assertEquals(1.5d, ParameterCoercion.coerce(ServiceParameterType.DOUBLE, "1.5"));
        assertEquals(true, ParameterCoercion.coerce(ServiceParameterType.BOOLEAN, "true"));
        assertEquals(false, ParameterCoercion.coerce(ServiceParameterType.BOOLEAN, false));
        assertEquals(List.of(1), ParameterCoercion.coerce(ServiceParameterType.JSON, "[1]"));
        assertNull(ParameterCoercion.coerce(ServiceParameterType.JSON, "null"));

        assertThrows(IllegalArgumentException.class,
                () -> ParameterCoercion.coerce(ServiceParameterType.INTEGER, "1.0"));
        assertThrows(IllegalArgumentException.class,
                () -> ParameterCoercion.coerce(ServiceParameterType.INTEGER, 1.5d));
        assertThrows(IllegalArgumentException.class,
                () -> ParameterCoercion.coerce(ServiceParameterType.BOOLEAN, "TRUE"));
        assertThrows(IllegalArgumentException.class,
                () -> ParameterCoercion.coerce(ServiceParameterType.STRING, Map.of("a", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> ParameterCoercion.coerce(ServiceParameterType.DOUBLE, Double.NaN));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ParameterCoercion.coerce(ServiceParameterType.INTEGER, "secret-value"));
        org.junit.jupiter.api.Assertions.assertFalse(failure.getMessage().contains("secret-value"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl workflow-engine test -Dtest=ParameterCoercionTest`

Expected: FAIL because `ParameterCoercion` does not exist.

- [ ] **Step 3: Write the minimal implementation**

`ServiceParameterException` has `(String message)` and `(String message, Throwable cause)`.

`ServiceParameterType` has the six constants.

`ParameterCoercion` rules:

- `STRING`: `String` stays. `Boolean` becomes `true` or `false`. `Integer`, `Long`, `Short`, and `Byte` become decimal digits. Finite integral `Double` or `Float` inside the long range becomes decimal digits. Any other finite double uses `Double.toString`. `Map` and `List` throw.
- `INTEGER` and `LONG`: `Integer`, `Long`, `Short`, and `Byte` use `longValue`. `Double` and `Float` must be finite and equal to `Math.rint`. A trimmed integer string uses `Long.parseLong`; `1.0` fails. `INTEGER` must fit the `int` range. `LONG` uses the parsed long.
- `DOUBLE`: a finite number, or a trimmed string parsed with `Double.parseDouble` that is finite. `NaN` and infinities throw.
- `BOOLEAN`: `Boolean`, or exactly `true` or `false`.
- `JSON`: `Map`, `List`, `Number`, and `Boolean` return as they are. A string is parsed with Jackson. A JSON null returns Java `null`. Any other shape throws. Jackson errors become `IllegalArgumentException("cannot coerce to JSON")` without the source text.

Every thrown `IllegalArgumentException` uses `cannot coerce to ` plus the type name.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl workflow-engine test -Dtest=ParameterCoercionTest`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/ServiceParameterException.java workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/ServiceParameterType.java workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/ParameterCoercion.java workflow-agent-service/workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/application/ParameterCoercionTest.java
git commit -m "feat: coerce service parameter values before the HTTP call"
```

---

### Task 8: Evaluate mappings in the service task

**Files:**
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/port/PublishedCallContract.java`
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/port/ServiceInputContract.java`
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/port/ServiceOutputContract.java`
- Modify: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/port/ServiceRegistryGateway.java`
- Modify: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ServiceRegistryDelegate.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/application/PublishedServiceInvoker.java`
- Delete: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/InputBinding.java`
- Modify: `workflow-agent-service/workflow-boot/src/main/java/io/github/illuseahashmap/workflow/serviceregistry/ServiceRegistryGatewayAdapter.java`
- Test: `workflow-agent-service/workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ServiceRegistryDelegateTest.java`
- Test: `workflow-agent-service/workflow-boot/src/test/java/io/github/illuseahashmap/workflow/serviceregistry/ServiceRegistryGatewayAdapterTest.java`

**Interfaces:**
- Consumes: `ParameterCoercion`, `ServiceParameterException`, `PublishedServiceInvoker`, `InvalidServiceRequestException`
- Produces:
  - `Optional<PublishedCallContract> findPublished(String serviceKey, String operationKey)`
  - `Map<String, Object> invoke(String serviceKey, String operationKey, Map<String, Object> resolvedInputs)`
  - `PublishedCallContract(List<ServiceInputContract> inputs, List<ServiceOutputContract> outputs)`
  - `ServiceInputContract(String name, ServiceParameterType type, boolean required)`
  - `ServiceOutputContract(String name, ServiceParameterType type)`
  - `PublishedServiceInvoker.findPublished` returns `Optional<PublishedOperation>`
  - `PublishedServiceInvoker.invoke(String, String, Map<String, Object>)` returns output parameter names to raw JSON values

- [ ] **Step 1: Write the failing test**

Rewrite `ServiceRegistryDelegateTest` so the delegate is constructed with a mocked `ProcessEngineConfigurationImpl` whose `getExpressionManager()` returns `new DefaultExpressionManager(Map.of())`.

The gateway mock:

```java
when(gateway.findPublished("crm", "createLead")).thenReturn(Optional.of(new PublishedCallContract(
        List.of(new ServiceInputContract("customerName", ServiceParameterType.STRING, true)),
        List.of(new ServiceOutputContract("id", ServiceParameterType.LONG)))));
```

Stub `execution.hasVariable("customerName")` as true and `execution.getVariable("customerName")` as `"Ann"` for the expression test.

Cases:

- Input mapping `[{"name":"customerName","expression":"${customerName}"}]` and output mapping `[{"name":"id","variable":"leadId"}]`. `invoke` returns `Map.of("id", 4)`. Verify `invoke("crm", "createLead", Map.of("customerName", "Ann"))` and `setVariable("leadId", 4L)`.
- Expression `ACTIVE` resolves as the literal string `ACTIVE` and is passed to `invoke`.
- Required input with expression `   ` throws `ServiceParameterException` and `invoke` is never called.
- Expression `${missing}` throws when the execution does not have the variable. Assert the exception is `ServiceParameterException`, the cause is kept, and the message contains `customerName` but not a resolved value. `invoke` is not called.
- Resolved value `1.5` for an `INTEGER` input throws `ServiceParameterException` before `invoke`.
- Input mapping `[{"name":"customerName","location":"BODY"}]` for a required `customerName` throws before `invoke`.
- Input mapping `[{"name":"gone","expression":"${gone}"}]` with an empty input contract still calls `invoke` with an empty map.
- `invoke` returns `Map.of("id", "nope")` for a `LONG` output. The delegate throws `ServiceParameterException` and does not call `setVariable`.
- `ServiceRegistryCallException` codes `SERVICE_NOT_PUBLISHED`, `LEAD_CONFLICT`, `HTTP_ERROR`, and `SERVICE_UNAVAILABLE` become `BpmnError` with those codes.
- `findPublished` returns `Optional.empty()` and becomes `BpmnError` `SERVICE_NOT_PUBLISHED` without calling `invoke`.

Add adapter tests:

```java
@Test
void returnsPublishedContractAndResolvedCall() {
    var operation = new PublishedOperation(
            "createLead", HttpMethod.POST, "https://crm.example/leads",
            AuthType.NONE, null, null, null, 1000, List.of(), "HTTP_ERROR",
            List.of(new InputParameter("name", ParameterType.STRING, true, ParameterLocation.BODY)),
            List.of(new OutputParameter("id", ParameterType.LONG, "id")));
    when(invoker.findPublished("crm", "createLead")).thenReturn(Optional.of(operation));
    when(invoker.invoke("crm", "createLead", Map.of("name", "Ann"))).thenReturn(Map.of("id", 4));
    var adapter = new ServiceRegistryGatewayAdapter(invoker);

    assertThat(adapter.findPublished("crm", "createLead")).hasValueSatisfying(contract ->
            assertThat(contract.inputs().getFirst().required()).isTrue());
    assertThat(adapter.invoke("crm", "createLead", Map.of("name", "Ann"))).containsEntry("id", 4);
}

@Test
void mapsInvalidRequestToParameterException() {
    when(invoker.invoke("crm", "createLead", Map.of()))
            .thenThrow(new InvalidServiceRequestException("URL still contains a placeholder"));
    var adapter = new ServiceRegistryGatewayAdapter(invoker);

    assertThatThrownBy(() -> adapter.invoke("crm", "createLead", Map.of()))
            .isInstanceOf(ServiceParameterException.class)
            .hasMessageContaining("crm")
            .hasMessageContaining("createLead")
            .hasMessageContaining("URL still contains a placeholder");
}
```

Keep the existing mapping of `ServiceCallException` to `ServiceRegistryCallException`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl workflow-engine,workflow-boot -am test -Dtest=ServiceRegistryDelegateTest,ServiceRegistryGatewayAdapterTest`

Expected: FAIL because `findPublished` does not exist.

- [ ] **Step 3: Write the minimal implementation**

`PublishedServiceInvoker`:

```java
public Optional<PublishedOperation> findPublished(String serviceKey, String operationKey) {
    return catalog.published(serviceKey, operationKey);
}

public Map<String, Object> invoke(String serviceKey, String operationKey, Map<String, Object> resolvedInputs) {
    PublishedOperation operation = catalog.published(serviceKey, operationKey)
            .orElseThrow(() -> new ServiceCallException("SERVICE_NOT_PUBLISHED"));
    CallOutcome outcome = caller.call(operation, resolvedInputs == null ? Map.of() : resolvedInputs);
    if (outcome.failed()) {
        throw new ServiceCallException(outcome.bpmnErrorCode());
    }
    return outcome.variables();
}
```

Delete the JSON binding parser and delete `InputBinding.java`.

`ServiceRegistryGatewayAdapter.findPublished` maps `ParameterType.name()` to `ServiceParameterType.valueOf`. It copies name, type, and required for inputs, and name and type for outputs. It does not copy location or `jsonField`.

`invoke` catches `ServiceCallException` and throws `ServiceRegistryCallException`. It catches `InvalidServiceRequestException` and throws `ServiceParameterException` with message `service ` + serviceKey + ` operation ` + operationKey + `: ` + exception.getMessage().

`ServiceRegistryDelegate` takes `ServiceRegistryGateway` and `ProcessEngineConfigurationImpl`, and stores `configuration.getExpressionManager()`.

`execute`:

1. Read the binding as it does now.
2. Call `findPublished`. Empty becomes `BpmnError("SERVICE_NOT_PUBLISHED")`.
3. Parse input and output JSON. An entry without `expression` or `variable` is ignored. A blank variable is ignored.
4. For each contract input, look up the expression by name. Trim it. Blank means no value. Otherwise evaluate. `null` means no value.
5. Required with no value throws `ServiceParameterException` with message `service <serviceKey> operation <operationKey> parameter <name>: required value is missing`.
6. Optional with no value is omitted.
7. Coerce a present value. `ParameterCoercion` returning null is no value and uses the same required rule. `IllegalArgumentException` becomes `ServiceParameterException` with message `service <serviceKey> operation <operationKey> parameter <name>: cannot coerce value` and the coercion exception as cause. Do not append the value.
8. An expression that throws is wrapped in `ServiceParameterException` with reason `expression failed` and that cause.
9. Call `invoke` inside the existing `ServiceRegistryCallException` catch, which still throws `BpmnError`.
10. Build a local output map. For each contract output with a mapping variable, skip names absent from the invoke result. Coerce the rest. One failure throws before any `setVariable`.
11. After every output coerces, call `setVariable` for each entry.

Evaluation:

```java
private Object evaluate(DelegateExecution execution, String expression) {
    if (expression.contains("${") || expression.contains("#{")) {
        return expressionManager.createExpression(expression).getValue(execution);
    }
    return expression;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl workflow-engine,workflow-boot -am test -Dtest=ServiceRegistryDelegateTest,ServiceRegistryGatewayAdapterTest,ParameterCoercionTest`

Run: `mvn -pl service-registry test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/port/PublishedCallContract.java workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/port/ServiceInputContract.java workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/port/ServiceOutputContract.java workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/port/ServiceRegistryGateway.java workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ServiceRegistryDelegate.java workflow-agent-service/workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ServiceRegistryDelegateTest.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/application/PublishedServiceInvoker.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/InputBinding.java workflow-agent-service/workflow-boot/src/main/java/io/github/illuseahashmap/workflow/serviceregistry/ServiceRegistryGatewayAdapter.java workflow-agent-service/workflow-boot/src/test/java/io/github/illuseahashmap/workflow/serviceregistry/ServiceRegistryGatewayAdapterTest.java
git commit -m "feat: evaluate service task mappings with Flowable expressions"
```

---

### Task 9: Bind the published contract on the node

**Files:**
- Create: `workflow-agent-web/src/features/service-registry/serviceCallMapping.ts`
- Create: `workflow-agent-web/src/features/service-registry/serviceCallMapping.spec.ts`
- Create: `workflow-agent-web/src/features/process-definition/serviceTaskPanel.ts`
- Create: `workflow-agent-web/src/features/process-definition/serviceTaskPanel.spec.ts`
- Modify: `workflow-agent-web/src/features/service-registry/api.ts`
- Modify: `workflow-agent-web/src/features/service-registry/ServiceCallConfigPanel.vue`
- Test: `workflow-agent-web/src/features/service-registry/ServiceCallConfigPanel.spec.ts`
- Modify: `workflow-agent-web/src/features/process-definition/views/ProcessDesignerView.vue`

**Interfaces:**
- Consumes: published parameter fields from Task 4
- Produces:
  - `buildInputRows(parameters, saved)`
  - `buildOutputRows(parameters, saved)`
  - `normalizeOutputVariable(candidate, previous, parameterName)`
  - `missingRequired(rows)`
  - `showsApprovalMode(isTask, isServiceTask)`

- [ ] **Step 1: Write the failing test**

`serviceCallMapping.spec.ts`:

```typescript
import { describe, expect, it } from 'vitest'
import {
  buildInputRows,
  buildOutputRows,
  missingRequired,
  normalizeOutputVariable,
} from './serviceCallMapping'

describe('service call mapping', () => {
  it('fills defaults and keeps a saved blank expression when the name matches', () => {
    const rows = buildInputRows(
      [
        { name: 'customerName', type: 'STRING', required: true, location: 'BODY' },
        { name: 'orderId', type: 'STRING', required: true, location: 'PATH' },
      ],
      [{ name: 'customerName', expression: '' }],
    )
    expect(rows.map((row) => row.expression)).toEqual(['', '${orderId}'])
  })

  it('drops names that are not on the next operation', () => {
    const rows = buildOutputRows(
      [{ name: 'id', type: 'LONG', jsonField: 'id' }],
      [{ name: 'gone', variable: 'old' }],
    )
    expect(rows).toEqual([{ name: 'id', type: 'LONG', jsonField: 'id', variable: 'id' }])
  })

  it('reports empty required expressions and repairs variable names', () => {
    expect(missingRequired([{ name: 'orderId', required: true, expression: '  ' }])).toEqual(['orderId'])
    expect(normalizeOutputVariable('lead-id', 'leadId', 'id')).toBe('leadId')
    expect(normalizeOutputVariable('lead-id', 'bad-name', 'id')).toBe('id')
    expect(normalizeOutputVariable('leadId', 'id', 'id')).toBe('leadId')
  })
})
```

`serviceTaskPanel.spec.ts`:

```typescript
import { describe, expect, it } from 'vitest'
import { showsApprovalMode } from './serviceTaskPanel'

describe('service task panel', () => {
  it('hides approval mode on a service task without treating every task as hidden', () => {
    expect(showsApprovalMode(true, true)).toBe(false)
    expect(showsApprovalMode(true, false)).toBe(true)
    expect(showsApprovalMode(false, false)).toBe(false)
  })
})
```

Create `workflow-agent-web/src/features/service-registry/ServiceCallConfigPanel.spec.ts`:

```typescript
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ServiceCallConfigPanel from './ServiceCallConfigPanel.vue'

const slot = { template: '<div><slot /></div>' }

describe('ServiceCallConfigPanel', () => {
  it('shows the published contract and the current mapping rows', () => {
    const wrapper = mount(ServiceCallConfigPanel, {
      props: {
        serviceKey: 'crm',
        operationKey: 'createLead',
        services: [
          {
            serviceKey: 'crm',
            name: 'CRM',
            operations: [
              {
                operationKey: 'createLead',
                method: 'POST',
                urlTemplate: 'https://draft.example',
                authType: 'NONE',
                secretPresent: false,
                timeoutMillis: 1000,
                errorStatusCodes: [],
                bpmnErrorCode: 'DRAFT_ERROR',
                publishedUrl: 'https://crm.example/orders/${orderId}',
                publishedMethod: 'POST',
                publishedBpmnErrorCode: 'HTTP_ERROR',
                inputParameters: [],
                outputParameters: [],
                publishedInputParameters: [],
                publishedOutputParameters: [],
              },
            ],
          },
        ],
        inputRows: [
          { name: 'orderId', type: 'STRING', required: true, location: 'PATH', expression: '${orderId}' },
        ],
        outputRows: [{ name: 'id', type: 'LONG', jsonField: 'id', variable: 'leadId' }],
      },
      global: {
        stubs: {
          'el-form': slot,
          'el-form-item': slot,
          'el-select': slot,
          'el-option': slot,
          'el-input': { props: ['modelValue'], template: '<input :value="modelValue" />' },
        },
      },
    })

    expect(wrapper.text()).toContain('POST')
    expect(wrapper.text()).toContain('https://crm.example/orders/${orderId}')
    expect(wrapper.text()).toContain('HTTP_ERROR')
    expect(wrapper.text()).not.toContain('DRAFT_ERROR')
    expect(wrapper.findAll('input').map((input) => input.element.value)).toEqual(['${orderId}', 'leadId'])
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm test:unit:run src/features/service-registry/serviceCallMapping.spec.ts src/features/process-definition/serviceTaskPanel.spec.ts src/features/service-registry/ServiceCallConfigPanel.spec.ts`

Expected: FAIL because the modules do not exist.

- [ ] **Step 3: Write the minimal implementation**

`serviceCallMapping.ts` exports the row builders. A saved input entry matches by `name` and supplies `expression` even when it is empty. A parameter with no saved entry gets an expression of `${` plus the parameter name plus `}`. Output rows do the same with `variable`, defaulting to the parameter name. `missingRequired` returns names whose trimmed expression is empty. `PARAMETER_NAME` is `/^[A-Za-z_][A-Za-z0-9_]{0,127}$/`. `normalizeOutputVariable` returns the candidate when it matches, otherwise the previous value when that matches, otherwise the parameter name.

`showsApprovalMode` returns `isTask && !isServiceTask`.

Extend `CatalogOperation` in `api.ts` with the draft and published parameter arrays, `publishedMethod`, and `publishedBpmnErrorCode`.

`ServiceCallConfigPanel` receives the selected keys, services, input rows, and output rows. It lists services as it does now, and operations that have `publishedUrl`. Under the selects, show `publishedMethod`, `publishedUrl`, and `publishedBpmnErrorCode` as read-only text. Render one expression input per input row and one variable input per output row. Emit `update:inputRows` and `update:outputRows`.

In `ProcessDesignerView`, store `serviceInputRows` and `serviceOutputRows`. When a Service Task is selected, parse `inputMapping` and `outputMapping`. After `loadPublishedServices` resolves, rebuild the rows from the published parameters and the parsed mappings. Changing the service clears the operation and both row lists. Changing the operation rebuilds rows with `buildInputRows` and `buildOutputRows`, passing the previous rows as the saved mappings.

`applyServiceCall` writes:

```typescript
inputMapping: JSON.stringify(serviceInputRows.value.map((row) => ({
  name: row.name,
  expression: row.expression,
})))
outputMapping: JSON.stringify(serviceOutputRows.value.map((row) => ({
  name: row.name,
  variable: normalizeOutputVariable(row.variable, row.variable, row.name),
})))
```

Before writing, if `missingRequired` is not empty, call `ElMessage.warning` with `Tham số bắt buộc chưa có biểu thức: ` plus the names. Still write the BPMN. If `normalizeOutputVariable` changes a variable, call `ElMessage.warning('Tên biến không hợp lệ')` and store the repaired name in the row as well as the BPMN.

Replace the «Loại bước» `v-if="isTask"` with `v-if="showsApprovalMode(isTask, isServiceTask)"`. Leave the computed `isTask` and the listener block as they are.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm test:unit:run src/features/service-registry/serviceCallMapping.spec.ts src/features/process-definition/serviceTaskPanel.spec.ts src/features/service-registry/ServiceCallConfigPanel.spec.ts`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/features/service-registry workflow-agent-web/src/features/process-definition/serviceTaskPanel.ts workflow-agent-web/src/features/process-definition/serviceTaskPanel.spec.ts workflow-agent-web/src/features/process-definition/views/ProcessDesignerView.vue
git commit -m "feat: map published service parameters on the service task"
```

---

### Task 10: Edit parameters in the catalog

**Files:**
- Create: `workflow-agent-web/src/features/service-registry/parameterDraft.ts`
- Create: `workflow-agent-web/src/features/service-registry/parameterDraft.spec.ts`
- Modify: `workflow-agent-web/src/features/service-registry/ServiceCatalogView.vue`
- Test: `workflow-agent-web/src/features/service-registry/ServiceCatalogView.spec.ts`

**Interfaces:**
- Consumes: `SaveOperationCommand` from Task 9, which now includes `inputParameters` and `outputParameters`
- Produces: `pathRequirement(location, required)` returns `{ required: boolean, locked: boolean }`. `PATH` forces `required` true and `locked` true. Any other location returns the given `required` and `locked` false.

- [ ] **Step 1: Write the failing test**

```typescript
import { describe, expect, it } from 'vitest'
import { pathRequirement } from './parameterDraft'

describe('parameter draft', () => {
  it('locks a path parameter as required and leaves other locations editable', () => {
    expect(pathRequirement('PATH', false)).toEqual({ required: true, locked: true })
    expect(pathRequirement('QUERY', true)).toEqual({ required: true, locked: false })
    expect(pathRequirement('BODY', false)).toEqual({ required: false, locked: false })
  })
})
```

Create `workflow-agent-web/src/features/service-registry/ServiceCatalogView.spec.ts`:

```typescript
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ServiceCatalogView from './ServiceCatalogView.vue'

vi.mock('./api', () => ({
  serviceCatalogApi: {
    list: vi.fn().mockResolvedValue([
      {
        serviceKey: 'crm',
        name: 'CRM',
        operations: [
          {
            operationKey: 'createLead',
            method: 'POST',
            urlTemplate: 'https://crm.example/orders/${orderId}',
            authType: 'NONE',
            secretPresent: false,
            bodyTemplate: '{"sku":1}',
            timeoutMillis: 1000,
            errorStatusCodes: ['409'],
            bpmnErrorCode: 'HTTP_ERROR',
            inputParameters: [
              { name: 'orderId', type: 'STRING', required: true, location: 'PATH' },
            ],
            outputParameters: [{ name: 'id', type: 'LONG', jsonField: 'id' }],
            publishedInputParameters: [],
            publishedOutputParameters: [],
          },
        ],
      },
    ]),
    create: vi.fn(),
    saveOperation: vi.fn(),
    publish: vi.fn(),
  },
}))

const slot = { template: '<div><slot /></div>' }

describe('ServiceCatalogView', () => {
  it('loads a draft operation and locks a path parameter', async () => {
    const wrapper = mount(ServiceCatalogView, {
      global: {
        stubs: {
          'el-form': slot,
          'el-form-item': slot,
          'el-select': slot,
          'el-option': slot,
          'el-button': { template: '<button type="button"><slot /></button>' },
          'el-input': { props: ['modelValue'], template: '<input :value="modelValue" />' },
          'el-input-number': { props: ['modelValue'], template: '<input :value="modelValue" />' },
          'el-checkbox': {
            props: ['modelValue', 'disabled'],
            template: '<input type="checkbox" :disabled="disabled" />',
          },
        },
      },
    })
    await flushPromises()
    await wrapper.get('button[data-operation="createLead"]').trigger('click')

    expect(wrapper.findAll('input').some((input) => input.element.value === '{"sku":1}')).toBe(true)
    const checkbox = wrapper.get('input[type="checkbox"]')
    expect((checkbox.element as HTMLInputElement).disabled).toBe(true)
  })
})
```

The operation row is a `button` with `data-operation` set to the operation key. The required checkbox for a `PATH` row is disabled. The secret input stays empty.

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm test:unit:run src/features/service-registry/parameterDraft.spec.ts src/features/service-registry/ServiceCatalogView.spec.ts`

Expected: FAIL because `parameterDraft` does not exist and the view has no parameter tables.

- [ ] **Step 3: Write the minimal implementation**

`parameterDraft.ts`:

```typescript
export function pathRequirement(location: string, required: boolean) {
  if (location === 'PATH') {
    return { required: true, locked: true }
  }
  return { required, locked: false }
}
```

`ServiceCatalogView` keeps the current service and operation fields. Add `bodyTemplate`. Add input rows `{ name, type, required, location }` and output rows `{ name, type, jsonField }`. Changing a location assigns `pathRequirement(location, row.required)` back onto that row. The required checkbox uses `:disabled="pathRequirement(row.location, row.required).locked"`.

Render each operation as a `button` with `data-operation` set to `operationKey`. Clicking one copies the draft operation into the form, including both parameter tables and `bodyTemplate`. Leave the secret input empty.

`saveOperation` sends `inputParameters` and `outputParameters` with the existing payload. The API error path stays `ElMessage.error(getErrorMessage(error))`.

Add the parameter arrays to `SaveOperationCommand` in `api.ts` if Task 9 did not already add them.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm test:unit:run src/features/service-registry`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/features/service-registry
git commit -m "feat: edit operation parameters in the service catalog"
```
