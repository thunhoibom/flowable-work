# Service Catalog Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Teach the service catalog environment base URLs, shared auth, relative paths, multiple BPMN error rules, extra success statuses, and separate connect/read timeouts, without changing published JSON calls.

**Architecture:** Service-level description, base URLs, and shared auth live on the service and inside the publication snapshot. An operation keeps its own auth mode (`INHERIT`, `OVERRIDE`, `NONE`). The invoker resolves the active environment and inherited auth before `RequestFactory` builds the HTTP request. Old snapshots that are a JSON array of operations stay valid.

**Tech Stack:** Java 21, Spring Boot, JDBC, Flyway, JUnit 5, Vue 3, Element Plus, Vitest.

## Global Constraints

- Process nodes keep only `serviceKey`, `operationKey`, input expressions, and output variable names.
- Runtime reads the published snapshot only. Draft edits do not affect a running process.
- Active environment key is `workflow.services.environment`. Default value is `default`.
- A path starting with `http://` or `https://` is absolute. Any other path joins the environment base URL with exactly one `/`.
- Missing publication, missing operation, or a relative path whose environment has no base URL throws `SERVICE_NOT_PUBLISHED`.
- Timeout or connection loss throws `SERVICE_UNAVAILABLE`.
- A status that is not a success and matches no error rule throws `HTTP_ERROR`.
- Success is every `2xx` status plus the operation's extra success statuses.
- The first matching error rule wins. Patterns are one code (`409`), a class (`5xx`), or a range (`400-499`).
- Existing operations migrate as absolute URLs, auth mode `OVERRIDE`, content type `JSON`, and one error rule built from the old status list and BPMN code. The old `timeoutMillis` is copied to both connect and read timeouts.
- Slice 1 auth types are only `NONE`, `BEARER`, `BASIC`, and `API_KEY` on a header.
- New operations default to auth mode `INHERIT` and content type `JSON`.
- Retry, redirect, and ignore-error policies are stored. Runtime still performs one attempt, follows the JDK HTTP client redirects, and never ignores errors.
- Secrets stay encrypted, are not returned by the catalog API, and are not written to process variables or logs.
- An empty secret field on save keeps the stored secret.
- This plan is slice 1 of `docs/superpowers/specs/2026-11-02-service-catalog-flowable-work-design.md`. Slices 2 through 8 get their own plans.

---

### Task 1: HTTP status ranges

**Files:**
- Create: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/HttpStatusMatcherTest.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/HttpStatusMatcher.java`

**Interfaces:**
- Consumes: nothing
- Produces: `HttpStatusMatcher.matches(int status, List<String> patterns)` also returns true for `5xx` and `400-499`

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HttpStatusMatcherTest {

    @Test
    void matchesExactClassAndRange() {
        assertTrue(HttpStatusMatcher.matches(409, List.of("409")));
        assertTrue(HttpStatusMatcher.matches(503, List.of("5xx")));
        assertTrue(HttpStatusMatcher.matches(404, List.of("400-499")));
        assertFalse(HttpStatusMatcher.matches(500, List.of("400-499")));
        assertFalse(HttpStatusMatcher.matches(200, List.of("5xx")));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run from `workflow-agent-service`:

```bash
mvn -pl service-registry -am test -Dtest=HttpStatusMatcherTest
```

Expected: FAIL because ranges are not matched.

- [ ] **Step 3: Accept class and range patterns**

In `matches`, trim each pattern and compare case-insensitively. Keep the existing exact code and `NXX` checks. Add:

```java
int dash = pattern.indexOf('-');
if (dash > 0) {
    int start = Integer.parseInt(pattern.substring(0, dash).trim());
    int end = Integer.parseInt(pattern.substring(dash + 1).trim());
    if (status >= start && status <= end) {
        return true;
    }
}
```

A pattern that is not a number range must not throw. Catch `NumberFormatException` and continue.

- [ ] **Step 4: Run the test to verify it passes**

Run the same Maven command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/HttpStatusMatcher.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/HttpStatusMatcherTest.java
git commit -m "feat: match HTTP status ranges"
```

### Task 2: Join a base URL and a path

**Files:**
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/RequestUrl.java`
- Create: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/RequestUrlTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `RequestUrl.join(String baseUrl, String path)` returns a string. Absolute paths are returned unchanged. A null or blank base URL with a relative path throws `InvalidServiceRequestException`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RequestUrlTest {

    @Test
    void keepsAbsoluteUrlsAndJoinsRelativePaths() {
        assertEquals("https://crm.example/orders", RequestUrl.join(null, "https://crm.example/orders"));
        assertEquals("http://crm.example/orders", RequestUrl.join("https://ignored", "http://crm.example/orders"));
        assertEquals("https://crm.example/orders", RequestUrl.join("https://crm.example/", "/orders"));
        assertEquals("https://crm.example/orders", RequestUrl.join("https://crm.example", "orders"));
        assertThrows(InvalidServiceRequestException.class, () -> RequestUrl.join(" ", "/orders"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl service-registry -am test -Dtest=RequestUrlTest
```

Expected: FAIL with class `RequestUrl` not found.

- [ ] **Step 3: Implement the join**

```java
package io.github.illuseahashmap.serviceregistry.domain;

public final class RequestUrl {

    private RequestUrl() {
    }

    public static String join(String baseUrl, String path) {
        if (path != null && (path.startsWith("https://") || path.startsWith("http://"))) {
            return path;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new InvalidServiceRequestException("Environment base URL is required");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String suffix = path == null ? "" : path;
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return base + suffix;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/RequestUrl.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/RequestUrlTest.java
git commit -m "feat: join service base URLs to operation paths"
```

### Task 3: Resolve inherited auth

**Files:**
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/AuthMode.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/AuthConfig.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/AuthInheritance.java`
- Create: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/AuthInheritanceTest.java`

**Interfaces:**
- Consumes: `AuthType`
- Produces: `enum AuthMode { INHERIT, OVERRIDE, NONE }`. `record AuthConfig(AuthType type, String apiKeyHeader, String secret)`. `AuthInheritance.resolve(AuthMode mode, AuthConfig shared, AuthConfig operation)` returns an `AuthConfig`. `NONE` returns `new AuthConfig(AuthType.NONE, null, null)`. `INHERIT` returns `shared`. `OVERRIDE` returns `operation`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuthInheritanceTest {

    @Test
    void selectsSharedOperationOrNoAuth() {
        AuthConfig shared = new AuthConfig(AuthType.BEARER, null, "shared-token");
        AuthConfig own = new AuthConfig(AuthType.API_KEY, "X-Api-Key", "op-token");
        assertEquals(shared, AuthInheritance.resolve(AuthMode.INHERIT, shared, own));
        assertEquals(own, AuthInheritance.resolve(AuthMode.OVERRIDE, shared, own));
        assertEquals(new AuthConfig(AuthType.NONE, null, null), AuthInheritance.resolve(AuthMode.NONE, shared, own));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl service-registry -am test -Dtest=AuthInheritanceTest
```

Expected: FAIL with missing types.

- [ ] **Step 3: Add the types and resolver**

`AuthMode` is the enum above. `AuthConfig` is the record above. `AuthInheritance.resolve` returns the three branches from the test. Slice 1 does not accept query API keys, OAuth, mTLS, HMAC, or AWS signatures.

- [ ] **Step 4: Run the test to verify it passes**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/AuthMode.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/AuthConfig.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/AuthInheritance.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/AuthInheritanceTest.java
git commit -m "feat: resolve inherited service auth"
```

### Task 4: Classify several error rules and extra success codes

**Files:**
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ErrorRule.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/PublishedOperation.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/CallClassifier.java`
- Modify: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/CallClassifierTest.java`

**Interfaces:**
- Consumes: `HttpStatusMatcher.matches`
- Produces: `record ErrorRule(java.util.List<String> patterns, String bpmnErrorCode)`. The existing 12-argument `PublishedOperation` constructor remains and means auth mode `OVERRIDE`, content type `JSON`, one error rule, no extra success codes, and the same connect and read timeout. New accessors: `authMode()`, `errorRules()`, `successStatuses()`, `connectTimeoutMillis()`, `readTimeoutMillis()`. `timeoutMillis()` returns the read timeout. `errorStatusCodes()` and `bpmnErrorCode()` return the first rule so current JDBC and tests keep compiling.

- [ ] **Step 1: Write the failing test**

Add this test to `CallClassifierTest`:

```java
@Test
void usesTheFirstMatchingRuleAndTreatsDeclaredStatusesAsSuccess() {
    var operation = new PublishedOperation(
            "createLead", HttpMethod.POST, "https://crm.example/leads",
            AuthMode.OVERRIDE, AuthType.NONE, null, null, null,
            1000, 2500,
            java.util.List.of(
                    new ErrorRule(java.util.List.of("409"), "LEAD_CONFLICT"),
                    new ErrorRule(java.util.List.of("5xx"), "UNAVAILABLE")),
            java.util.List.of("404"),
            java.util.List.of(),
            java.util.List.of());
    assertFalse(CallClassifier.classify(operation, 404, "{\"id\":\"L1\"}").failed());
    assertEquals("L1", CallClassifier.classify(operation, 404, "{\"id\":\"L1\"}").variables().get("id"));
    assertEquals("LEAD_CONFLICT", CallClassifier.classify(operation, 409, "{}").bpmnErrorCode());
    assertEquals("UNAVAILABLE", CallClassifier.classify(operation, 503, "{}").bpmnErrorCode());
    assertEquals("HTTP_ERROR", CallClassifier.classify(operation, 401, "{}").bpmnErrorCode());
}
```

The new constructor used here is the extended one from Step 3. Do not change the existing test. It must still pass through the compatibility constructor.

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl service-registry -am test -Dtest=CallClassifierTest
```

Expected: FAIL because the extended constructor and rule matching do not exist.

- [ ] **Step 3: Extend the operation and classifier**

Add `ErrorRule`. Change `PublishedOperation` so its canonical components are:

```java
String operationKey,
HttpMethod method,
String urlTemplate,
AuthMode authMode,
AuthType authType,
String apiKeyHeader,
String secret,
String bodyTemplate,
int connectTimeoutMillis,
int readTimeoutMillis,
List<ErrorRule> errorRules,
List<String> successStatuses,
List<InputParameter> inputs,
List<OutputParameter> outputs
```

Keep a constructor with the old 12 parameters that delegates with `AuthMode.OVERRIDE`, `List.of(new ErrorRule(errorStatusCodes, bpmnErrorCode))`, `List.of()` success statuses, and both timeouts set from `timeoutMillis`. Add `timeoutMillis()`, `errorStatusCodes()`, and `bpmnErrorCode()` as methods, not record components.

In `CallClassifier.classify`, before reading the body:

```java
if (!isSuccess(operation, status)) {
    for (ErrorRule rule : operation.errorRules()) {
        if (HttpStatusMatcher.matches(status, rule.patterns())) {
            return new CallOutcome(blankToHttpError(rule.bpmnErrorCode()), Map.of());
        }
    }
    return new CallOutcome("HTTP_ERROR", Map.of());
}
```

`isSuccess` is `status >= 200 && status < 300`, or `HttpStatusMatcher.matches(status, operation.successStatuses())`. Keep the existing JSON object output mapping for success. Do not add raw, status, or header outputs in this task.

Update `InMemoryServiceCatalog.saveOperation` and `ServiceCatalogController.operation` only if they call the canonical constructor directly. They should call the old 12-argument constructor so current behavior stays `OVERRIDE`.

- [ ] **Step 4: Run the classifier and request factory tests**

```bash
mvn -pl service-registry -am test -Dtest=CallClassifierTest,RequestFactoryTest,ServiceCallerTest
```

Expected: PASS. Existing JSON calls still classify `409` as the old BPMN code and `404` as `HTTP_ERROR`.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ErrorRule.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/PublishedOperation.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/CallClassifier.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/CallClassifierTest.java
git commit -m "feat: classify multiple service error rules"
```

### Task 5: Build a request with a base URL

**Files:**
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/RequestFactory.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCaller.java`
- Modify: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/RequestFactoryTest.java`

**Interfaces:**
- Consumes: `RequestUrl.join`, `PublishedOperation.authMode` is not read here. The caller passes the effective secret and auth already copied onto the operation.
- Produces: `RequestFactory.create(PublishedOperation operation, Map<String, Object> resolvedInputs, String baseUrl)`. The old two-argument `create` delegates with `baseUrl = null`. `ServiceCaller.call(operation, inputs, baseUrl)` does the same.

- [ ] **Step 1: Write the failing test**

Add to `RequestFactoryTest`:

```java
@Test
void joinsARelativePathAndKeepsAnAbsoluteUrl() {
    var relative = new PublishedOperation(
            "createLead", HttpMethod.GET, "/orders/${orderId}",
            AuthType.NONE, null, null, null, 1000, List.of(), "HTTP_ERROR",
            List.of(new InputParameter("orderId", ParameterType.STRING, true, ParameterLocation.PATH)),
            List.of());
    var request = RequestFactory.create(relative, Map.of("orderId", "15"), "https://crm.example/");
    assertEquals("https://crm.example/orders/15", request.url());

    var absolute = new PublishedOperation(
            "createLead", HttpMethod.GET, "https://other.example/orders",
            AuthType.NONE, null, null, null, 1000, List.of(), "HTTP_ERROR",
            List.of(), List.of());
    assertEquals("https://other.example/orders", RequestFactory.create(absolute, Map.of(), null).url());
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl service-registry -am test -Dtest=RequestFactoryTest
```

Expected: FAIL because `create` has no base URL argument.

- [ ] **Step 3: Join before placeholder replacement**

Add the three-argument `create`. The first line that currently assigns `String url = operation.urlTemplate()` becomes `String url = RequestUrl.join(baseUrl, operation.urlTemplate())`. The two-argument method calls the new one with `null`. Add `ServiceCaller.call(operation, inputs, baseUrl)` and keep the old method delegating with `null`. Do not add retry.

- [ ] **Step 4: Run the test to verify it passes**

Expected: PASS, including the existing request factory tests.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/RequestFactory.java workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCaller.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/RequestFactoryTest.java
git commit -m "feat: build service requests from a base URL"
```

### Task 6: Publish service settings with the operations

**Files:**
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/EnvironmentBaseUrl.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/PublishedService.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ServiceDraft.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ServiceView.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCatalog.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/InMemoryServiceCatalog.java`
- Modify: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCatalogTest.java`

**Interfaces:**
- Consumes: `AuthConfig`, `AuthMode`, `PublishedOperation`
- Produces: `record EnvironmentBaseUrl(String environment, String url)`. `record PublishedService(String serviceKey, String name, String description, List<EnvironmentBaseUrl> baseUrls, AuthConfig sharedAuth, List<PublishedOperation> operations)`. `ServiceDraft` and `ServiceView` gain `description`, `baseUrls`, and `sharedAuth`. `ServiceCatalog.publishedService(String serviceKey)` returns `Optional<PublishedService>`. `saveService(String serviceKey, String name)` still updates only the name. Add `saveServiceSettings(String serviceKey, String name, String description, List<EnvironmentBaseUrl> baseUrls, AuthConfig sharedAuth)`.

- [ ] **Step 1: Write the failing test**

Add to `ServiceCatalogTest`:

```java
@Test
void publishesBaseUrlAndSharedAuthWithoutChangingADraftEdit() {
    catalog.saveService("crm", "CRM");
    catalog.saveServiceSettings("crm", "CRM", "Khách hàng",
            List.of(new EnvironmentBaseUrl("default", "https://crm.example")),
            new AuthConfig(AuthType.BEARER, null, "published-secret"));
    catalog.saveOperation("crm", operation("/leads"));
    catalog.publish("crm");
    catalog.saveServiceSettings("crm", "CRM", "Nháp",
            List.of(new EnvironmentBaseUrl("default", "https://draft.example")),
            new AuthConfig(AuthType.NONE, null, null));

    PublishedService published = catalog.publishedService("crm").orElseThrow();
    assertEquals("https://crm.example", published.baseUrls().getFirst().url());
    assertEquals("published-secret", published.sharedAuth().secret());
    assertEquals("Nháp", catalog.find("crm").orElseThrow().description());
}
```

`ServiceView` in this test must expose `description()`.

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl service-registry -am test -Dtest=ServiceCatalogTest
```

Expected: FAIL because settings and `publishedService` do not exist.

- [ ] **Step 3: Store a published copy separately from the draft**

`ServiceDraft` keeps a constructor `(serviceKey, name, operations)` that delegates with an empty description, an empty base URL list, and `new AuthConfig(AuthType.NONE, null, null)`. `InMemoryServiceCatalog` keeps the draft and, on `publish`, copies description, base URLs, shared auth, and operations into a `PublishedService`. `publishedService` reads that copy. `published(serviceKey, operationKey)` still returns the operation from that copy. `saveService` keeps updating the draft name only. `find` returns the draft. `JdbcServiceCatalog.find` compiles in this task by passing those same empty defaults. Task 7 replaces that placeholder mapping with the stored columns.

Do not put the shared secret into `OperationView`.

- [ ] **Step 4: Run the test to verify it passes**

Expected: PASS, including the existing catalog tests.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCatalogTest.java
git commit -m "feat: publish service base URLs and shared auth"
```

### Task 7: Persist the new service settings

**Files:**
- Create: `workflow-agent-service/platform-migrations/src/main/resources/db/migration/platform/V34__service_catalog_foundation.sql`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/infrastructure/JdbcServiceCatalog.java`
- Modify: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/infrastructure/JdbcServiceCatalogTest.java`

**Interfaces:**
- Consumes: `saveServiceSettings`, `publishedService`, `EnvironmentBaseUrl`, `AuthConfig`
- Produces: draft columns and a snapshot object. A snapshot whose JSON root is an array still loads as operations only, with no base URLs and shared auth `NONE`.

- [ ] **Step 1: Write the failing JDBC test**

In `applyMigration`, after V33, execute `/db/migration/platform/V34__service_catalog_foundation.sql`. Add this test:

```java
@Test
void publishedSettingsStayUntilTheNextPublishAndLegacyArraySnapshotsStillLoad() throws Exception {
    applyMigration();
    var catalog = new JdbcServiceCatalog(dataSource(), new ServiceSecretCipher(KEY));
    catalog.saveService("crm", "CRM");
    catalog.saveServiceSettings("crm", "CRM", "Khách hàng",
            List.of(new EnvironmentBaseUrl("default", "https://crm.example")),
            new AuthConfig(AuthType.BEARER, null, "published-secret"));
    catalog.saveOperation("crm", operation("https://crm.example/leads", null));
    catalog.publish("crm");
    catalog.saveServiceSettings("crm", "CRM", "Nháp",
            List.of(new EnvironmentBaseUrl("default", "https://draft.example")),
            new AuthConfig(AuthType.NONE, null, null));
    assertEquals("https://crm.example", catalog.publishedService("crm").orElseThrow().baseUrls().getFirst().url());
    assertEquals("published-secret", catalog.publishedService("crm").orElseThrow().sharedAuth().secret());

    try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
         Statement statement = connection.createStatement()) {
        statement.executeUpdate("""
                UPDATE service_publication
                SET snapshot_json = '[{"operationKey":"createLead","method":"POST","urlTemplate":"https://legacy.example","authType":"NONE","timeoutMillis":1000,"errorStatusCodes":[],"bpmnErrorCode":"HTTP_ERROR","inputParameters":[],"outputParameters":[]}]'
                WHERE service_key = 'crm'
                """);
    }
    assertEquals("https://legacy.example", catalog.published("crm", "createLead").orElseThrow().urlTemplate());
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl service-registry -am test -Dtest=JdbcServiceCatalogTest
```

Expected: FAIL because the columns and snapshot object do not exist.

- [ ] **Step 3: Add the migration and snapshot object**

`V34__service_catalog_foundation.sql`:

```sql
ALTER TABLE service_definition
    ADD COLUMN description VARCHAR(2000) NOT NULL DEFAULT '',
    ADD COLUMN base_urls_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN shared_auth_json TEXT NOT NULL DEFAULT '{"type":"NONE","apiKeyHeader":null}';

ALTER TABLE service_operation_draft
    ADD COLUMN auth_mode VARCHAR(16) NOT NULL DEFAULT 'OVERRIDE',
    ADD COLUMN connect_timeout_millis INTEGER NOT NULL DEFAULT 5000;

UPDATE service_operation_draft
SET connect_timeout_millis = timeout_millis;
```

`shared_auth_json` stores type, header name, and the encrypted secret under `secretCipher`. The API never returns that cipher. `timeout_millis` remains the read timeout. Also add `auth_mode VARCHAR(16) NOT NULL DEFAULT 'OVERRIDE'`, `error_rules_json TEXT NOT NULL DEFAULT '[]'`, `success_statuses VARCHAR(256) NOT NULL DEFAULT ''`, and `call_policy_json TEXT NOT NULL DEFAULT '{"maxAttempts":1,"retryStatuses":[],"backoffMillis":0,"followRedirects":true,"ignoreErrors":false}'`. `saveOperation` writes `auth_mode`, both timeouts, error rules, success statuses, and the call policy. An empty `error_rules_json` loads as one rule from `error_status_codes` and `bpmn_error_code`. Add `authMode`, `connectTimeoutMillis`, `errorRules`, and `successStatuses` to `OperationView`. Do not read `call_policy_json` from the invoker in this plan.

Replace the publication root. New snapshots are:

```json
{"description":"","baseUrls":[],"sharedAuth":{"type":"NONE"},"operations":[]}
```

`readSnapshot` checks the first non-space character. `[` means the legacy operation array. `{` means the new object. `saveServiceSettings` updates the three service columns and does not publish. `publish` writes the new object from the current draft.

- [ ] **Step 4: Run the JDBC test to verify it passes**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/platform-migrations/src/main/resources/db/migration/platform/V34__service_catalog_foundation.sql workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/infrastructure/JdbcServiceCatalog.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/infrastructure/JdbcServiceCatalogTest.java
git commit -m "feat: store published service connection settings"
```

### Task 8: Call the published base URL and inherited auth

**Files:**
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/application/ServiceEnvironment.java`
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/application/PublishedServiceInvoker.java`
- Modify: `workflow-agent-service/workflow-boot/src/main/java/io/github/illuseahashmap/workflow/serviceregistry/ServiceRegistryConfiguration.java`
- Create: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/application/PublishedServiceInvokerTest.java`

**Interfaces:**
- Consumes: `publishedService`, `AuthInheritance.resolve`, `RequestUrl.join` through `ServiceCaller.call(operation, inputs, baseUrl)`
- Produces: `fun interface ServiceEnvironment { String name(); }`. The boot configuration supplies `workflow.services.environment`, default `default`. `PublishedServiceInvoker` constructor becomes `(ServiceCatalog catalog, ServiceCaller caller, ServiceEnvironment environment)`.

- [ ] **Step 1: Write the failing test**

Use an in-memory catalog. Publish a service with base URL `https://crm.example` for `default`, shared bearer secret `shared-token`, and an operation whose path is `/leads`, auth mode `INHERIT`, and auth type `NONE`. Invoke it with a fake transport. Assert the outbound URL is `https://crm.example/leads` and the `Authorization` header is `Bearer shared-token`. A second case with no base URL for `default` and a relative path throws `ServiceCallException` with `SERVICE_NOT_PUBLISHED`. An absolute published URL still calls when the base URL list is empty.

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl service-registry -am test -Dtest=PublishedServiceInvokerTest
```

Expected: FAIL because the invoker still loads only the operation.

- [ ] **Step 3: Resolve the snapshot before calling**

`invoke` loads `publishedService`. Missing service or operation throws `ServiceCallException("SERVICE_NOT_PUBLISHED")`. Find the base URL whose environment equals `environment.name()`. For a relative path and no match, throw the same error. Copy the resolved `AuthConfig` onto the operation with `AuthMode.OVERRIDE` before `caller.call(operation, inputs, baseUrl)`. Pass `null` as the base URL for an absolute path. Leave `ServiceCaller` at one attempt.

Update the boot configuration constructor call. Set the property default in `workflow-boot` application configuration:

```yaml
workflow:
  services:
    environment: default
```

- [ ] **Step 4: Run the invoker test and the boot adapter test**

```bash
mvn -pl service-registry,workflow-boot -am test -Dtest=PublishedServiceInvokerTest,ServiceRegistryGatewayAdapterTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/application workflow-agent-service/workflow-boot/src/main/java/io/github/illuseahashmap/workflow/serviceregistry/ServiceRegistryConfiguration.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/application/PublishedServiceInvokerTest.java
git commit -m "feat: invoke published services in the active environment"
```

### Task 9: Expose settings on the catalog API

**Files:**
- Modify: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/interfaces/ServiceCatalogController.java`
- Modify: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/interfaces/ServiceCatalogControllerTest.java`

**Interfaces:**
- Consumes: `saveServiceSettings`, `ServiceView` fields `description`, `baseUrls`, `sharedAuth`
- Produces: `PUT /services/{serviceKey}/settings` body `{"name":"CRM","description":"Khách hàng","baseUrls":[{"environment":"default","url":"https://crm.example"}],"sharedAuth":{"type":"BEARER","apiKeyHeader":null,"secret":"s3cret"}}`. `GET /services/{serviceKey}` returns description, base URLs, and shared auth type plus header name. It returns `secretPresent` and never `secret`. `POST /services` remains name and key only. Operation save accepts `authMode`, `connectTimeoutMillis`, `errorRules`, and `successStatuses`. Missing `authMode` means `OVERRIDE` so current clients stay valid.

- [ ] **Step 1: Write the failing test**

In `ServiceCatalogControllerTest`, create `crm`, put settings, get the service, and expect the description, base URL, auth type `BEARER`, and `sharedAuth.secretPresent == true`. Assert the response body does not contain `s3cret`. Put the same settings with a blank secret and assert the stored secret is still present.

- [ ] **Step 2: Run the test to verify it fails**

```bash
mvn -pl service-registry -am test -Dtest=ServiceCatalogControllerTest
```

Expected: FAIL with 405 or a missing JSON path.

- [ ] **Step 3: Add the settings route**

Add `@PutMapping("/{serviceKey}/settings")`. Validate that name and every base URL environment and URL are not blank. Reject an auth type outside `NONE`, `BEARER`, `BASIC`, and `API_KEY` with HTTP 400. `API_KEY` requires a header name. A blank secret loads the current draft auth secret before `saveServiceSettings`. Map the view with `secretPresent` instead of the secret string.

- [ ] **Step 4: Run the test to verify it passes**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/interfaces/ServiceCatalogController.java workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/interfaces/ServiceCatalogControllerTest.java
git commit -m "feat: edit service connection settings through the catalog API"
```

### Task 10: Edit the settings on the services screen

**Files:**
- Modify: `workflow-agent-web/src/features/service-registry/api.ts`
- Modify: `workflow-agent-web/src/features/service-registry/ServiceCatalogView.vue`
- Modify: `workflow-agent-web/src/features/service-registry/ServiceCatalogView.spec.ts`
- Modify: `workflow-agent-web/src/features/service-registry/operationDraft.ts`
- Modify: `workflow-agent-web/src/features/service-registry/OperationEditor.vue`

**Interfaces:**
- Consumes: `PUT /services/{serviceKey}/settings` and operation `authMode` plus `connectTimeoutMillis`
- Produces: the service dialog edits description, base URL rows, and shared auth. The operation dialog adds auth mode and connect timeout. Read timeout stays the current timeout field.

- [ ] **Step 1: Write the failing view test**

Extend the existing rename test setup. After opening Sửa dịch vụ, set description `Khách hàng`, one base URL environment `default` and URL `https://crm.example`, and shared auth `BEARER`. Click the settings save button. Expect:

```ts
expect(serviceCatalogApi.saveSettings).toHaveBeenCalledWith('crm', {
  name: 'CRM',
  description: 'Khách hàng',
  baseUrls: [{ environment: 'default', url: 'https://crm.example' }],
  sharedAuth: { type: 'BEARER', apiKeyHeader: '', secret: '' },
})
```

Mock `saveSettings` in the existing `vi.mock('./api')` block.

- [ ] **Step 2: Run the test to verify it fails**

From `workflow-agent-web`:

```bash
node_modules/.bin/vitest.cmd --run src/features/service-registry/ServiceCatalogView.spec.ts
```

Expected: FAIL because `saveSettings` and the new fields are absent.

- [ ] **Step 3: Add the fields and the client method**

`serviceCatalogApi.saveSettings` sends `PUT /services/{serviceKey}/settings`. Extend `ServiceView` with `description`, `baseUrls`, and `sharedAuth: { type, apiKeyHeader, secretPresent }`. The edit dialog shows those inputs and the existing disabled service key. Saving a blank name still warns `Thiếu tên dịch vụ` and does not call the API.

In the operation draft, add `authMode` default `INHERIT` for a new operation and `OVERRIDE` when loading an operation that has no mode. Add `connectTimeoutMillis`, defaulting to the loaded read timeout. Add `errorRules` as `{ patterns: string, bpmnErrorCode: string }[]` and `successStatuses` as one comma-separated input. The shared auth select offers only Không, Bearer, Basic, and API key. The operation editor shows auth mode, connect timeout, the error rule rows, and the success status input. The API key header input remains visible only when the effective type is `API_KEY` and the mode is `OVERRIDE`. Inherited mode hides the operation secret input. The save payload sends `authMode`, `connectTimeoutMillis`, `errorRules`, and `successStatuses`.

- [ ] **Step 4: Run the view test to verify it passes**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web/src/features/service-registry/api.ts workflow-agent-web/src/features/service-registry/ServiceCatalogView.vue workflow-agent-web/src/features/service-registry/ServiceCatalogView.spec.ts workflow-agent-web/src/features/service-registry/operationDraft.ts workflow-agent-web/src/features/service-registry/OperationEditor.vue
git commit -m "feat: edit service base URLs and shared auth in the catalog"
```
