# Service Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gọi HTTP từ BPMN qua một service catalog: sơ đồ chỉ giữ `serviceKey` và `operationKey`, lần chạy đọc snapshot publish mới nhất.

**Architecture:** Module `service-registry` giữ catalog, snapshot và HTTP client. `workflow-engine` chỉ có port và Service Task delegate. `workflow-boot` nối port với module. UI thêm catalog và panel mapping, không có ô URL trên node.

**Tech Stack:** Java 25, Spring Boot 4.1, JUnit 5, Flyway, PostgreSQL, Flowable 8 process engine, Vue 3, bpmn-js.

## Global Constraints

- Method chỉ `GET`, `POST`, `PUT`, `PATCH`, `DELETE`.
- Auth chỉ None, Bearer tĩnh, Basic, API key trên một header.
- Body và response là JSON. URL được phép chứa `${var}`.
- `2xx` ghi output và đi tiếp.
- Status khớp danh sách lỗi của operation ném `BpmnError` với mã cấu hình, mặc định `HTTP_ERROR`.
- Status khác ngoài `2xx` ném `BpmnError` `HTTP_ERROR`.
- Timeout hoặc mất kết nối ném `BpmnError` `SERVICE_UNAVAILABLE`.
- Chưa publish hoặc không có operation ném `BpmnError` `SERVICE_NOT_PUBLISHED`.
- Runtime chỉ đọc snapshot publish mới nhất. Sửa nháp không đổi lần chạy.
- Secret mã hóa khi lưu, không trả đầy đủ qua API, không ghi vào biến process hay log.
- BPMN extension namespace `http://workflow-agent.local/bpmn`.
- Không làm OpenAPI import, Agent, MCP, multipart, OAuth2, retry, hay dán URL lên node.
- `serviceKey` duy nhất. `operationKey` duy nhất trong một service.

---

### Task 1: HTTP status matcher

**Files:**
- Create: `workflow-agent-service/service-registry/pom.xml`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/HttpStatusMatcher.java`
- Test: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/HttpStatusMatcherTest.java`
- Modify: `workflow-agent-service/pom.xml` (thêm `<module>service-registry</module>` sau `rules-engine`)

**Interfaces:**
- Consumes: không
- Produces: `HttpStatusMatcher.matches(int status, List<String> patterns): boolean`

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HttpStatusMatcherTest {

    @Test
    void matchesExactCodeAndClassPattern() {
        assertTrue(HttpStatusMatcher.matches(409, List.of("400", "409", "5XX")));
        assertTrue(HttpStatusMatcher.matches(503, List.of("400", "409", "5XX")));
        assertFalse(HttpStatusMatcher.matches(404, List.of("400", "409", "5XX")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl service-registry test -Dtest=HttpStatusMatcherTest`
Expected: FAIL, `HttpStatusMatcher` không tồn tại.

- [ ] **Step 3: Write minimal implementation**

`pom.xml` của module copy cấu trúc `rules-engine/pom.xml`, `artifactId` là `service-registry`, jacoco line `0.80`, branch `0.70`.

```java
package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

public final class HttpStatusMatcher {

    private HttpStatusMatcher() {
    }

    public static boolean matches(int status, List<String> patterns) {
        String code = Integer.toString(status);
        for (String pattern : patterns) {
            if (pattern.endsWith("XX") && code.charAt(0) == pattern.charAt(0)) {
                return true;
            }
            if (pattern.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl service-registry test -Dtest=HttpStatusMatcherTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/pom.xml workflow-agent-service/service-registry
git commit -m "feat: match HTTP status patterns for service calls"
```

### Task 2: Build the outbound request

**Files:**
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/AuthType.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/HttpMethod.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ParameterLocation.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/InputBinding.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/PublishedOperation.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/OutboundRequest.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/RequestFactory.java`
- Test: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/RequestFactoryTest.java`

**Interfaces:**
- Consumes: không
- Produces:
  - `enum HttpMethod { GET, POST, PUT, PATCH, DELETE }`
  - `enum AuthType { NONE, BEARER, BASIC, API_KEY }`
  - `enum ParameterLocation { PATH, QUERY, HEADER, BODY }`
  - `record InputBinding(String name, ParameterLocation location)`
  - `record PublishedOperation(String operationKey, HttpMethod method, String urlTemplate, AuthType authType, String apiKeyHeader, String secret, String bodyTemplate, int timeoutMillis, java.util.List<String> errorStatusCodes, String bpmnErrorCode)`
  - `record OutboundRequest(HttpMethod method, String url, java.util.Map<String, String> headers, String body, int timeoutMillis)`
  - `RequestFactory.create(PublishedOperation operation, java.util.Map<String, Object> variables, java.util.List<InputBinding> inputs): OutboundRequest`

- [ ] **Step 1: Write the failing test**

```java
@Test
void buildsPostWithPathQueryBodyAndBearer() {
    var operation = new PublishedOperation(
            "createLead", HttpMethod.POST, "https://crm.example/orders/${orderId}",
            AuthType.BEARER, null, "s3cret",
            "{\"name\":null}", 5000, java.util.List.of("409"), "HTTP_ERROR");
    var request = RequestFactory.create(operation, java.util.Map.of(
            "orderId", "9", "q", "open", "customerName", "Ann"), java.util.List.of(
            new InputBinding("orderId", ParameterLocation.PATH),
            new InputBinding("q", ParameterLocation.QUERY),
            new InputBinding("customerName", ParameterLocation.BODY)));

    org.junit.jupiter.api.Assertions.assertEquals("https://crm.example/orders/9?q=open", request.url());
    org.junit.jupiter.api.Assertions.assertEquals("Bearer s3cret", request.headers().get("Authorization"));
    org.junit.jupiter.api.Assertions.assertTrue(request.body().contains("\"customerName\":\"Ann\""));
}
```

```java
@Test
void placesApiKeyInNamedHeader() {
    var operation = new PublishedOperation(
            "createLead", HttpMethod.GET, "https://crm.example/leads",
            AuthType.API_KEY, "X-Api-Key", "s3cret",
            null, 1000, java.util.List.of(), "HTTP_ERROR");
    var request = RequestFactory.create(operation, java.util.Map.of("trace", "1"),
            java.util.List.of(new InputBinding("trace", ParameterLocation.HEADER)));
    org.junit.jupiter.api.Assertions.assertEquals("s3cret", request.headers().get("X-Api-Key"));
    org.junit.jupiter.api.Assertions.assertEquals("1", request.headers().get("trace"));
    org.junit.jupiter.api.Assertions.assertFalse(request.headers().containsKey("Authorization"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl service-registry test -Dtest=RequestFactoryTest`
Expected: FAIL, thiếu type.

- [ ] **Step 3: Write minimal implementation**

`RequestFactory` thay `${name}` trong URL bằng giá trị biến đã URL-encode. Binding `QUERY` thêm query string. Binding `HEADER` thêm header. Binding `BODY` ghi đè field cùng tên trong JSON body. Không log `secret`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl service-registry test -Dtest=RequestFactoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry
git commit -m "feat: build HTTP requests from published operations"
```

### Task 3: Classify the call result

**Files:**
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/OutputBinding.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/CallOutcome.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/CallClassifier.java`
- Test: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/CallClassifierTest.java`

**Interfaces:**
- Consumes: `PublishedOperation`, `HttpStatusMatcher`
- Produces:
  - `record OutputBinding(String jsonField, String variableName)`
  - `record CallOutcome(String bpmnErrorCode, java.util.Map<String, Object> variables)`
  - `CallClassifier.classify(PublishedOperation operation, int status, String responseBody, java.util.List<OutputBinding> outputs): CallOutcome`
  - `CallOutcome.failed(): boolean` khi `bpmnErrorCode != null`

- [ ] **Step 1: Write the failing test**

```java
@Test
void mapsIdOnSuccessAndUsesConfiguredErrorCode() {
    var operation = new PublishedOperation(
            "createLead", HttpMethod.POST, "https://crm.example/leads",
            AuthType.NONE, null, null, null, 1000, java.util.List.of("409"), "LEAD_CONFLICT");
    var outputs = java.util.List.of(new OutputBinding("id", "leadId"));

    var success = CallClassifier.classify(operation, 201, "{\"id\":\"L1\"}", outputs);
    org.junit.jupiter.api.Assertions.assertFalse(success.failed());
    org.junit.jupiter.api.Assertions.assertEquals("L1", success.variables().get("leadId"));

    var conflict = CallClassifier.classify(operation, 409, "{}", outputs);
    org.junit.jupiter.api.Assertions.assertEquals("LEAD_CONFLICT", conflict.bpmnErrorCode());

    var other = CallClassifier.classify(operation, 404, "{}", outputs);
    org.junit.jupiter.api.Assertions.assertEquals("HTTP_ERROR", other.bpmnErrorCode());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl service-registry test -Dtest=CallClassifierTest`
Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

`2xx` parse JSON và copy các field trong `OutputBinding`. Status khớp `errorStatusCodes` trả mã `bpmnErrorCode` của operation, nếu mã đó blank thì `HTTP_ERROR`. Mọi status còn lại trả `HTTP_ERROR`. Không đưa response body vào message lỗi.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl service-registry test -Dtest=CallClassifierTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry
git commit -m "feat: classify service call responses"
```

### Task 4: Execute HTTP with a fake client

**Files:**
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/HttpTransport.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/TransportResponse.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCaller.java`
- Test: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCallerTest.java`

**Interfaces:**
- Consumes: `RequestFactory`, `CallClassifier`, `PublishedOperation`, `InputBinding`, `OutputBinding`
- Produces:
  - `interface HttpTransport { TransportResponse send(OutboundRequest request) throws java.io.IOException; }`
  - `record TransportResponse(int status, String body)`
  - `ServiceCaller.call(PublishedOperation operation, java.util.Map<String, Object> variables, java.util.List<InputBinding> inputs, java.util.List<OutputBinding> outputs): CallOutcome`
  - Transport ném `java.io.IOException` thì `CallOutcome` có mã `SERVICE_UNAVAILABLE`

- [ ] **Step 1: Write the failing test**

```java
@Test
void returnsUnavailableWhenTransportFails() {
    HttpTransport transport = request -> { throw new java.io.IOException("down"); };
    var caller = new ServiceCaller(transport);
    var operation = new PublishedOperation(
            "createLead", HttpMethod.GET, "https://crm.example/leads",
            AuthType.NONE, null, null, null, 1000, java.util.List.of(), "HTTP_ERROR");
    var outcome = caller.call(operation, java.util.Map.of(), java.util.List.of(), java.util.List.of());
    org.junit.jupiter.api.Assertions.assertEquals("SERVICE_UNAVAILABLE", outcome.bpmnErrorCode());
}
```

Thêm test transport trả status và body, caller gọi `send` với method của operation.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl service-registry test -Dtest=ServiceCallerTest`
Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

`ServiceCaller` gọi `RequestFactory.create`, rồi `transport.send`. `IOException` thành outcome `SERVICE_UNAVAILABLE`. Response thì đưa vào `CallClassifier.classify`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl service-registry test -Dtest=ServiceCallerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry
git commit -m "feat: execute a published service operation"
```

### Task 5: Publish an immutable snapshot

**Files:**
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCatalog.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/domain/ServiceDraft.java`
- Test: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/domain/ServiceCatalogTest.java`

**Interfaces:**
- Consumes: `PublishedOperation`
- Produces:
  - `record ServiceDraft(String serviceKey, String name, java.util.List<PublishedOperation> operations)`
  - `ServiceCatalog.saveDraft(ServiceDraft draft)`
  - `ServiceCatalog.publish(String serviceKey)`
  - `ServiceCatalog.published(String serviceKey, String operationKey): Optional<PublishedOperation>`
  - Draft đổi sau publish không đổi kết quả `published` cho đến lần `publish` sau

- [ ] **Step 1: Write the failing test**

```java
@Test
void publishedCallUsesSnapshotUntilNextPublish() {
    var catalog = new ServiceCatalog();
    catalog.saveDraft(new ServiceDraft("crm", "CRM", java.util.List.of(operation("https://old.example"))));
    org.junit.jupiter.api.Assertions.assertTrue(catalog.published("crm", "createLead").isEmpty());
    catalog.publish("crm");
    org.junit.jupiter.api.Assertions.assertEquals("https://old.example",
            catalog.published("crm", "createLead").orElseThrow().urlTemplate());
    catalog.saveDraft(new ServiceDraft("crm", "CRM", java.util.List.of(operation("https://new.example"))));
    org.junit.jupiter.api.Assertions.assertEquals("https://old.example",
            catalog.published("crm", "createLead").orElseThrow().urlTemplate());
    catalog.publish("crm");
    org.junit.jupiter.api.Assertions.assertEquals("https://new.example",
            catalog.published("crm", "createLead").orElseThrow().urlTemplate());
}
```

`operation` là helper tạo `PublishedOperation` với URL truyền vào.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl service-registry test -Dtest=ServiceCatalogTest`
Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

`ServiceCatalog` giữ draft hiện tại và một bản copy list operation lúc `publish`. `published` đọc bản copy, không đọc draft.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl service-registry test -Dtest=ServiceCatalogTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry
git commit -m "feat: publish immutable service snapshots"
```

### Task 6: Persist the catalog

**Files:**
- Create: `workflow-agent-service/platform-migrations/src/main/resources/db/migration/platform/V32__add_service_registry.sql`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/infrastructure/JdbcServiceCatalog.java`
- Modify: `workflow-agent-service/service-registry/pom.xml` thêm `spring-boot-starter-jdbc` và `spring-boot-starter-json` nếu cần Jackson
- Test: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/infrastructure/JdbcServiceCatalogTest.java`

**Interfaces:**
- Consumes: `ServiceCatalog` từ Task 5. Đổi `ServiceCatalog` thành interface với đúng các method đã có. Class in-memory đổi tên `InMemoryServiceCatalog`.
- Produces: `JdbcServiceCatalog implements ServiceCatalog`

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE service_definition (
    service_key VARCHAR(128) PRIMARY KEY,
    name VARCHAR(256) NOT NULL
);

CREATE TABLE service_operation_draft (
    service_key VARCHAR(128) NOT NULL REFERENCES service_definition(service_key),
    operation_key VARCHAR(128) NOT NULL,
    method VARCHAR(8) NOT NULL,
    url_template TEXT NOT NULL,
    auth_type VARCHAR(16) NOT NULL,
    api_key_header VARCHAR(128),
    secret_cipher TEXT,
    body_template TEXT,
    timeout_millis INTEGER NOT NULL,
    error_status_codes VARCHAR(256) NOT NULL,
    bpmn_error_code VARCHAR(128) NOT NULL,
    PRIMARY KEY (service_key, operation_key)
);

CREATE TABLE service_publication (
    service_key VARCHAR(128) PRIMARY KEY REFERENCES service_definition(service_key),
    snapshot_json TEXT NOT NULL,
    published_at TIMESTAMP NOT NULL
);
```

- [ ] **Step 2: Write the failing repository test**

Test dùng Testcontainers PostgreSQL, chạy SQL V32, save draft, publish, đọc snapshot, sửa draft, xác nhận URL publish cũ còn đến lần publish sau. Nếu module chưa có Testcontainers, thêm dependency `org.testcontainers:junit-jupiter` và `org.testcontainers:postgresql`, scope test.

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -pl service-registry test -Dtest=JdbcServiceCatalogTest`
Expected: FAIL

- [ ] **Step 4: Implement JdbcServiceCatalog**

`secret_cipher` lưu bằng AES-GCM, cùng kiểu `AesGcmAgentCredentialCipher` nhưng không gắn tenant vào cipher. API đọc draft trả `secretPresent=true` và không trả cipher. Snapshot JSON chứa secret đã mã hóa, chỉ `ServiceCaller` giải mã ngay trước khi gọi.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl service-registry test -Dtest=JdbcServiceCatalogTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add workflow-agent-service/platform-migrations workflow-agent-service/service-registry
git commit -m "feat: store service drafts and published snapshots"
```

### Task 7: Catalog REST API

**Files:**
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/interfaces/ServiceCatalogController.java`
- Test: `workflow-agent-service/service-registry/src/test/java/io/github/illuseahashmap/serviceregistry/interfaces/ServiceCatalogControllerTest.java`
- Modify: `workflow-agent-service/service-registry/pom.xml` thêm `spring-boot-starter-web` và `spring-boot-starter-test`

**Interfaces:**
- Consumes: `ServiceCatalog`
- Produces:
  - `POST /services` body `{ "serviceKey": "crm", "name": "CRM" }`
  - `PUT /services/{serviceKey}/operations/{operationKey}` body method, url, authType, apiKeyHeader, secret, bodyTemplate, timeoutMillis, errorStatusCodes, bpmnErrorCode
  - `POST /services/{serviceKey}/publish`
  - `GET /services/{serviceKey}` trả operation không có field secret, chỉ `secretPresent`

- [ ] **Step 1: Write the failing MockMvc test**

Tạo service, lưu operation với secret `s3cret`, GET catalog, assert response không chứa `s3cret`. Publish rồi GET published operation URL.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl service-registry test -Dtest=ServiceCatalogControllerTest`
Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

Controller gọi `ServiceCatalog`. Response DTO không có property secret.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl service-registry test -Dtest=ServiceCatalogControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/service-registry
git commit -m "feat: expose the service catalog API"
```

### Task 8: BPMN binding and delegate

**Files:**
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/application/port/ServiceRegistryGateway.java`
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/domain/ServiceTaskBinding.java`
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/XmlServiceTaskBindingParser.java`
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ServiceRegistryDelegate.java`
- Test: `workflow-agent-service/workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/XmlServiceTaskBindingParserTest.java`
- Test: `workflow-agent-service/workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ServiceRegistryDelegateTest.java`

**Interfaces:**
- Consumes: không phụ thuộc `service-registry`
- Produces:
  - `record ServiceTaskBinding(String activityId, String serviceKey, String operationKey, String inputMappingJson, String outputMappingJson)`
  - `XmlServiceTaskBindingParser.parse(String bpmnXml): List<ServiceTaskBinding>`
  - `interface ServiceRegistryGateway { java.util.Map<String, Object> invoke(String serviceKey, String operationKey, java.util.Map<String, Object> variables, String inputMappingJson, String outputMappingJson); }`
  - Gateway ném `ServiceRegistryCallException(String bpmnErrorCode)`
  - `@Component("serviceRegistryDelegate") ServiceRegistryDelegate implements JavaDelegate`

XML node:

```xml
<bpmn:serviceTask id="createLead">
  <bpmn:extensionElements>
    <workflow:serviceCall serviceKey="crm" operationKey="createLead"
        inputMapping='[{"name":"customerName","location":"BODY"}]'
        outputMapping='[{"jsonField":"id","variableName":"leadId"}]' />
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

- [ ] **Step 1: Write the failing parser test**

Parse XML trên. Assert `serviceKey` là `crm`, `operationKey` là `createLead`. Dùng XXE-disabled parse cùng `XmlAgentTaskBindingParser`.

- [ ] **Step 2: Write the failing delegate test**

Gateway ném `ServiceRegistryCallException("SERVICE_NOT_PUBLISHED")`. Delegate rethrow `org.flowable.engine.delegate.BpmnError` cùng mã. Gateway trả `Map.of("leadId", "L1")` thì delegate `setVariable("leadId", "L1")`.

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn -pl workflow-engine test -Dtest=XmlServiceTaskBindingParserTest,ServiceRegistryDelegateTest`
Expected: FAIL

- [ ] **Step 4: Write minimal implementation**

Parser chỉ đọc `workflow:serviceCall`. Delegate lấy biến từ `DelegateExecution`, gọi gateway, ghi map kết quả bằng `setVariable`. Exception thành `BpmnError`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl workflow-engine test -Dtest=XmlServiceTaskBindingParserTest,ServiceRegistryDelegateTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add workflow-agent-service/workflow-engine
git commit -m "feat: invoke a service registry operation from BPMN"
```

### Task 9: Wire the gateway in the boot module

**Files:**
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/infrastructure/JdkHttpTransport.java`
- Create: `workflow-agent-service/workflow-boot/src/main/java/io/github/illuseahashmap/workflow/serviceregistry/ServiceRegistryGatewayAdapter.java`
- Create: `workflow-agent-service/service-registry/src/main/java/io/github/illuseahashmap/serviceregistry/application/PublishedServiceInvoker.java`
- Test: `workflow-agent-service/workflow-boot/src/test/java/io/github/illuseahashmap/workflow/serviceregistry/ServiceRegistryGatewayAdapterTest.java`
- Modify: `workflow-agent-service/workflow-boot/pom.xml` thêm dependency `service-registry`

**Interfaces:**
- Consumes: `ServiceCatalog.published`, `ServiceCaller.call`
- Produces: `ServiceRegistryGatewayAdapter implements ServiceRegistryGateway`
- `PublishedServiceInvoker.invoke(...): java.util.Map<String, Object>` ném runtime exception có `bpmnErrorCode()` khi chưa publish hoặc outcome failed

- [ ] **Step 1: Write the failing adapter test**

Invoker trả map `leadId`. Adapter trả đúng map. Invoker ném mã `HTTP_ERROR`. Adapter ném `ServiceRegistryCallException` mã đó.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl workflow-boot test -Dtest=ServiceRegistryGatewayAdapterTest`
Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

Adapter chỉ chuyển kiểu, giống `AgentRunGatewayAdapter`. `PublishedServiceInvoker` đọc snapshot, gọi `ServiceCaller`, thiếu snapshot thì mã `SERVICE_NOT_PUBLISHED`.

`JdkHttpTransport implements HttpTransport` dùng `java.net.http.HttpClient`. `HttpRequest.timeout` bằng `OutboundRequest.timeoutMillis`. Timeout hoặc `IOException` ném ra cho `ServiceCaller` đổi thành `SERVICE_UNAVAILABLE`. Không ghi URL kèm secret và không ghi body response vào log.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl workflow-boot -am test -Dtest=ServiceRegistryGatewayAdapterTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/workflow-boot workflow-agent-service/service-registry
git commit -m "feat: connect BPMN service calls to the catalog"
```

### Task 10: Catalog screen and BPMN panel

**Files:**
- Modify: `workflow-agent-web/src/bpmn/workflowModdle.ts`
- Modify: `workflow-agent-web/src/utils/bpmn.ts`
- Modify: `workflow-agent-web/src/features/process-definition/views/ProcessDesignerView.vue`
- Create: `workflow-agent-web/src/features/service-registry/ServiceCatalogView.vue`
- Test: `workflow-agent-web/src/__tests__/bpmn.spec.ts`

**Interfaces:**
- Consumes: REST Task 7, extension XML Task 8
- Produces: moddle type `ServiceCall` với attr `serviceKey`, `operationKey`, `inputMapping`, `outputMapping`. Panel chọn service và operation đã publish. Không render input URL.

- [ ] **Step 1: Write the failing frontend test**

Trong `bpmn.spec.ts`, parse BPMN có `workflow:serviceCall serviceKey="crm" operationKey="createLead"`. Assert reader trả đúng hai key và không đọc attribute URL.

- [ ] **Step 2: Run test to verify it fails**

Run từ `workflow-agent-web`: `npm test -- src/__tests__/bpmn.spec.ts`
Expected: FAIL

- [ ] **Step 3: Write minimal implementation**

Thêm type vào `workflowModdle.ts`. Khi element là `serviceTask`, panel hiện hai select lấy từ `GET /services/{serviceKey}` bản publish. Lưu extension `workflow:serviceCall`. Màn catalog gọi POST service, PUT operation, POST publish.

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- src/__tests__/bpmn.spec.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-web
git commit -m "feat: configure BPMN service calls from the catalog"
```
