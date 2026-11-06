# Draft / Publish Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Saving a process stores a draft in the platform database. Only publishing deploys to Flowable. As a result, the latest non-suspended Flowable definition is always the running one.

**Spec:** `docs/superpowers/specs/2026-11-05-draft-publish-design.md`

**Architecture:**
- `ProcessDraftService` owns `workflow_process_draft`, with an optimistic `revision`.
- `ProcessPublishService` runs every deploy guard, deploys, copies assignment rules from the previous version, and writes `workflow_process_release`. All of this happens in one Spring transaction, which Flowable shares.
- Suspend, resume and rollback are Flowable definition operations plus a release row.
- Every reader that used `workflow_active_version` switches to "latest and not suspended".
- A one-off, idempotent migration command converts the current tenant.

**Tech Stack:** Java 25, Spring Boot 4.1, Flowable 8.0.0, PostgreSQL 17 + Flyway, JUnit 5, AssertJ, Mockito, Testcontainers, H2 (engine tests), Vue 3, Vitest, Element Plus.

## Global Constraints

- Flowable stays `8.0.0`. "Running" means `latestVersion()` and `!isSuspended()`, always filtered by tenant.
- A draft save never calls `RepositoryService.createDeployment`.
- Publish order: size and identity → `ScriptTaskDeploymentGuard` → Agent validator → `createDeployment` → key check → rule copy → release row → audit. A failure leaves the engine, the rules and the draft unchanged.
- `ProcessDeployer` is the only class that calls `createDeployment`. The subprocess plan adds its guards inside `ProcessDeployer.deployChecked`.
- `POST /deploy` with XML identical to the running version returns that version and does not create a new one.
- Suspend uses `suspendProcessDefinitionById(id, false, null)`, and resume uses `activateProcessDefinitionById(id, false, null)`. Instances are never suspended.
- Rollback deploys the old XML as a new version. It never "activates" an old version.
- Only a non-latest version without running instances can be deleted.
- `POST /deploy` stays for `SERVICE`/CI. It upserts the draft and publishes it in one call.
- After Task 7, nothing *reads* `workflow_active_version`. Delete statements that clean legacy rows may stay.
- Process key pattern `^[A-Za-z][A-Za-z0-9_-]{0,63}$`, the same as the web `normalizeProcessIdentity`.
- Hash: SHA-256 hex of the XML with `\r\n`/`\r` → `\n` and outer whitespace stripped.
- Migration `V38__process_draft_and_release.sql`. Write RLS as plain statements (no `DO $$` block), so the Testcontainers helper can split the script on `;`.
- New error code `DRAFT_REVISION_CONFLICT` maps to HTTP 409. The web uses it to show the reload/overwrite dialog.
- Endpoint roles: read endpoints use the read roles of `/definitions`; write endpoints use the roles of `/deploy`; the migration requires `WF_PLATFORM_ADMIN`.

Paths below use `<main>` = `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow` and `<test>` = `workflow-agent-service/workflow-engine/src/test/java/io/github/illuseahashmap/workflow`. Java commands run from `workflow-agent-service`. Web commands run from `workflow-agent-web`.

---

### Task 1: Migration

**Files:**
- Create: `workflow-agent-service/platform-migrations/src/main/resources/db/migration/platform/V38__process_draft_and_release.sql`

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE workflow_process_draft (
    tenant_id              VARCHAR(64)  NOT NULL,
    process_definition_key VARCHAR(128) NOT NULL,
    name                   VARCHAR(255) NOT NULL,
    bpmn_xml               TEXT         NOT NULL,
    xml_sha256             CHAR(64)     NOT NULL,
    revision               BIGINT       NOT NULL,
    updated_by             VARCHAR(255) NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_workflow_process_draft PRIMARY KEY (tenant_id, process_definition_key)
);

CREATE TABLE workflow_process_release (
    id                     BIGSERIAL    PRIMARY KEY,
    tenant_id              VARCHAR(64)  NOT NULL,
    process_definition_key VARCHAR(128) NOT NULL,
    process_definition_id  VARCHAR(128) NOT NULL,
    version                INTEGER      NOT NULL,
    action                 VARCHAR(16)  NOT NULL,
    source_version         INTEGER,
    xml_sha256             CHAR(64)     NOT NULL,
    performed_by           VARCHAR(255) NOT NULL,
    performed_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_workflow_process_release_action
        CHECK (action IN ('PUBLISH', 'ROLLBACK', 'SUSPEND', 'RESUME', 'MIGRATED'))
);

CREATE INDEX ix_workflow_process_release_key
    ON workflow_process_release (tenant_id, process_definition_key, performed_at DESC);
CREATE INDEX ix_workflow_process_release_definition
    ON workflow_process_release (tenant_id, process_definition_id, performed_at DESC);

ALTER TABLE workflow_process_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_process_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_workflow_process_draft ON workflow_process_draft
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE workflow_process_release ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_process_release FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_workflow_process_release ON workflow_process_release
    USING ((current_setting('app.platform_admin', true) = 'true'
            OR current_setting('app.system_worker', true) = 'true')
           OR tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK ((current_setting('app.platform_admin', true) = 'true'
                 OR current_setting('app.system_worker', true) = 'true')
                OR tenant_id = current_setting('app.tenant_id', true));
```

- [ ] **Step 2: Check it against every earlier migration**

Run: `mvn -pl workflow-boot -am test -Dtest=PlatformMigrationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS. Docker is required. If any later migration grants privileges per table to an application role, add the same grants for the two new tables.

- [ ] **Step 3: Commit**

```bash
git add workflow-agent-service/platform-migrations
git commit -m "feat: add process draft and release tables"
```

---

### Task 2: Domain types and hash

**Files:**
- Create: `<main>/process/domain/BpmnXmlHash.java`, `ProcessDraft.java`, `ProcessDraftHeader.java`, `ProcessRelease.java`, `ReleaseAction.java`
- Test: `<test>/process/domain/BpmnXmlHashTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.workflow.process.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BpmnXmlHashTest {

    @Test
    void ignoresLineEndingsAndOuterWhitespace() {
        assertThat(BpmnXmlHash.of("<a>\r\n<b/>\r\n</a>\n"))
                .isEqualTo(BpmnXmlHash.of("  <a>\n<b/>\n</a>"));
    }

    @Test
    void changesWhenContentChanges() {
        assertThat(BpmnXmlHash.of("<a/>")).isNotEqualTo(BpmnXmlHash.of("<b/>"));
    }

    @Test
    void isLowercaseHexOfSha256() {
        assertThat(BpmnXmlHash.of("<a/>")).matches("[0-9a-f]{64}");
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `mvn -pl workflow-engine -am test -Dtest=BpmnXmlHashTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: compilation failure, because `BpmnXmlHash` does not exist.

- [ ] **Step 3: Implement**

```java
package io.github.illuseahashmap.workflow.process.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Content hash of a BPMN document, stable across line-ending differences. */
public final class BpmnXmlHash {

    private BpmnXmlHash() {
    }

    public static String of(String bpmnXml) {
        String normalized = bpmnXml.replace("\r\n", "\n").replace('\r', '\n').strip();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
```

```java
package io.github.illuseahashmap.workflow.process.domain;

import java.time.OffsetDateTime;

public record ProcessDraft(
        String tenantId,
        String processDefinitionKey,
        String name,
        String bpmnXml,
        String xmlSha256,
        long revision,
        String updatedBy,
        OffsetDateTime updatedAt) {

    public static ProcessDraft of(String tenantId, String key, String name, String bpmnXml,
                                  long revision, String updatedBy, OffsetDateTime updatedAt) {
        return new ProcessDraft(tenantId, key, name, bpmnXml, BpmnXmlHash.of(bpmnXml),
                revision, updatedBy, updatedAt);
    }

    public ProcessDraft next(String newName, String newXml, String by, OffsetDateTime at) {
        return of(tenantId, processDefinitionKey, newName, newXml, revision + 1, by, at);
    }
}
```

```java
package io.github.illuseahashmap.workflow.process.domain;

import java.time.OffsetDateTime;

/** A draft without its XML, used by list pages. */
public record ProcessDraftHeader(
        String processDefinitionKey,
        String name,
        String xmlSha256,
        long revision,
        String updatedBy,
        OffsetDateTime updatedAt) {
}
```

```java
package io.github.illuseahashmap.workflow.process.domain;

public enum ReleaseAction {
    PUBLISH, ROLLBACK, SUSPEND, RESUME, MIGRATED
}
```

```java
package io.github.illuseahashmap.workflow.process.domain;

import java.time.OffsetDateTime;

public record ProcessRelease(
        Long id,
        String tenantId,
        String processDefinitionKey,
        String processDefinitionId,
        int version,
        ReleaseAction action,
        Integer sourceVersion,
        String xmlSha256,
        String performedBy,
        OffsetDateTime performedAt) {
}
```

- [ ] **Step 4: Run the test and see it pass**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/workflow-engine
git commit -m "feat: add process draft and release domain types"
```

---

### Task 3: Repositories

**Files:**
- Modify: `workflow-agent-service/workflow-engine/pom.xml` (test dependencies)
- Create: `<main>/process/application/port/ProcessDraftRepository.java`, `ProcessReleaseRepository.java`
- Create: `<main>/process/infrastructure/persistence/JdbcProcessDraftRepository.java`, `JdbcProcessReleaseRepository.java`
- Test: `<test>/process/infrastructure/persistence/JdbcProcessDraftRepositoryTest.java`

- [ ] **Step 1: Add test dependencies**

In `workflow-engine/pom.xml`, after the existing `org.testcontainers:junit-jupiter` dependency:

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.github.illuseahashmap</groupId>
            <artifactId>platform-migrations</artifactId>
            <version>${project.version}</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Define the ports**

```java
package io.github.illuseahashmap.workflow.process.application.port;

import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraftHeader;
import java.util.List;
import java.util.Optional;

public interface ProcessDraftRepository {

    Optional<ProcessDraft> find(String tenantId, String processDefinitionKey);

    /** Locks the row until the surrounding transaction ends. */
    Optional<ProcessDraft> findForUpdate(String tenantId, String processDefinitionKey);

    /** @return false when a draft with this key already exists */
    boolean insert(ProcessDraft draft);

    /** @return false when the stored revision is not {@code expectedRevision} */
    boolean update(ProcessDraft draft, long expectedRevision);

    List<ProcessDraftHeader> listHeaders(String tenantId);

    void delete(String tenantId, String processDefinitionKey);
}
```

```java
package io.github.illuseahashmap.workflow.process.application.port;

import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import java.util.List;
import java.util.Map;

public interface ProcessReleaseRepository {

    void append(ProcessRelease release);

    /** Newest first. */
    List<ProcessRelease> list(String tenantId, String processDefinitionKey);

    boolean hasAny(String tenantId, String processDefinitionKey);

    /** The newest release row per process definition id. */
    Map<String, ProcessRelease> latestByDefinition(String tenantId);

    void deleteByKey(String tenantId, String processDefinitionKey);
}
```

- [ ] **Step 3: Write the failing repository test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcProcessDraftRepositoryTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            String sql = new String(JdbcProcessDraftRepositoryTest.class.getResourceAsStream(
                    "/db/migration/platform/V38__process_draft_and_release.sql").readAllBytes(),
                    StandardCharsets.UTF_8);
            for (String command : sql.split(";")) {
                if (!command.isBlank()) {
                    statement.execute(command);
                }
            }
        }
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 5, 9, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void insertsOnceAndUpdatesOnlyWithTheExpectedRevision() {
        var drafts = new JdbcProcessDraftRepository(jdbc);
        ProcessDraft draft = ProcessDraft.of("t1", "order", "Đơn hàng", "<a/>", 1, "alice", NOW);

        assertThat(drafts.insert(draft)).isTrue();
        assertThat(drafts.insert(draft)).isFalse();

        ProcessDraft next = draft.next("Đơn hàng", "<b/>", "bob", NOW.plusMinutes(1));
        assertThat(drafts.update(next, 1)).isTrue();
        assertThat(drafts.update(next.next("x", "<c/>", "carol", NOW), 1)).isFalse();

        ProcessDraft stored = drafts.find("t1", "order").orElseThrow();
        assertThat(stored.revision()).isEqualTo(2);
        assertThat(stored.bpmnXml()).isEqualTo("<b/>");
        assertThat(stored.updatedBy()).isEqualTo("bob");
        assertThat(drafts.find("t2", "order")).isEmpty();
        assertThat(drafts.listHeaders("t1")).extracting("processDefinitionKey").containsExactly("order");
    }

    @Test
    void keepsTheNewestReleasePerDefinition() {
        var releases = new JdbcProcessReleaseRepository(jdbc);
        releases.append(release("pay:1:1", 1, ReleaseAction.PUBLISH, NOW));
        releases.append(release("pay:1:1", 1, ReleaseAction.SUSPEND, NOW.plusMinutes(5)));
        releases.append(release("pay:2:2", 2, ReleaseAction.PUBLISH, NOW.plusMinutes(6)));

        assertThat(releases.hasAny("t1", "pay")).isTrue();
        assertThat(releases.hasAny("t1", "other")).isFalse();
        assertThat(releases.list("t1", "pay")).extracting(ProcessRelease::version).containsExactly(2, 1, 1);
        assertThat(releases.latestByDefinition("t1").get("pay:1:1").action()).isEqualTo(ReleaseAction.SUSPEND);

        releases.deleteByKey("t1", "pay");
        assertThat(releases.hasAny("t1", "pay")).isFalse();
    }

    private static ProcessRelease release(String id, int version, ReleaseAction action, OffsetDateTime at) {
        return new ProcessRelease(null, "t1", "pay", id, version, action, null, "0".repeat(64), "alice", at);
    }
}
```

- [ ] **Step 4: Run it and see it fail**

Run: `mvn -pl workflow-engine -am test -Dtest=JdbcProcessDraftRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: compilation failure.

- [ ] **Step 5: Implement**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.persistence;

import io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraftHeader;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProcessDraftRepository implements ProcessDraftRepository {

    private static final String COLUMNS = """
            tenant_id, process_definition_key, name, bpmn_xml, xml_sha256, revision, updated_by, updated_at""";

    private final JdbcTemplate jdbc;

    public JdbcProcessDraftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProcessDraft> find(String tenantId, String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow_process_draft "
                        + "WHERE tenant_id = ? AND process_definition_key = ?",
                (rs, row) -> map(rs), tenantId, key).stream().findFirst();
    }

    @Override
    public Optional<ProcessDraft> findForUpdate(String tenantId, String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow_process_draft "
                        + "WHERE tenant_id = ? AND process_definition_key = ? FOR UPDATE",
                (rs, row) -> map(rs), tenantId, key).stream().findFirst();
    }

    @Override
    public boolean insert(ProcessDraft draft) {
        try {
            jdbc.update("INSERT INTO workflow_process_draft (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    draft.tenantId(), draft.processDefinitionKey(), draft.name(), draft.bpmnXml(),
                    draft.xmlSha256(), draft.revision(), draft.updatedBy(), draft.updatedAt());
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public boolean update(ProcessDraft draft, long expectedRevision) {
        return jdbc.update("""
                UPDATE workflow_process_draft
                SET name = ?, bpmn_xml = ?, xml_sha256 = ?, revision = ?, updated_by = ?, updated_at = ?
                WHERE tenant_id = ? AND process_definition_key = ? AND revision = ?
                """, draft.name(), draft.bpmnXml(), draft.xmlSha256(), draft.revision(),
                draft.updatedBy(), draft.updatedAt(),
                draft.tenantId(), draft.processDefinitionKey(), expectedRevision) == 1;
    }

    @Override
    public List<ProcessDraftHeader> listHeaders(String tenantId) {
        return jdbc.query("""
                SELECT process_definition_key, name, xml_sha256, revision, updated_by, updated_at
                FROM workflow_process_draft WHERE tenant_id = ? ORDER BY process_definition_key
                """, (rs, row) -> new ProcessDraftHeader(
                        rs.getString("process_definition_key"), rs.getString("name"),
                        rs.getString("xml_sha256"), rs.getLong("revision"), rs.getString("updated_by"),
                        rs.getObject("updated_at", OffsetDateTime.class)), tenantId);
    }

    @Override
    public void delete(String tenantId, String key) {
        jdbc.update("DELETE FROM workflow_process_draft WHERE tenant_id = ? AND process_definition_key = ?",
                tenantId, key);
    }

    private static ProcessDraft map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProcessDraft(rs.getString("tenant_id"), rs.getString("process_definition_key"),
                rs.getString("name"), rs.getString("bpmn_xml"), rs.getString("xml_sha256"),
                rs.getLong("revision"), rs.getString("updated_by"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
```

```java
package io.github.illuseahashmap.workflow.process.infrastructure.persistence;

import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProcessReleaseRepository implements ProcessReleaseRepository {

    private static final String COLUMNS = """
            id, tenant_id, process_definition_key, process_definition_id, version, action,
            source_version, xml_sha256, performed_by, performed_at""";

    private final JdbcTemplate jdbc;

    public JdbcProcessReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(ProcessRelease release) {
        jdbc.update("""
                INSERT INTO workflow_process_release (tenant_id, process_definition_key, process_definition_id,
                    version, action, source_version, xml_sha256, performed_by, performed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, release.tenantId(), release.processDefinitionKey(), release.processDefinitionId(),
                release.version(), release.action().name(), release.sourceVersion(), release.xmlSha256(),
                release.performedBy(), release.performedAt());
    }

    @Override
    public List<ProcessRelease> list(String tenantId, String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM workflow_process_release "
                        + "WHERE tenant_id = ? AND process_definition_key = ? ORDER BY performed_at DESC, id DESC",
                (rs, row) -> map(rs), tenantId, key);
    }

    @Override
    public boolean hasAny(String tenantId, String key) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM workflow_process_release WHERE tenant_id = ? AND process_definition_key = ?
                """, Integer.class, tenantId, key);
        return count != null && count > 0;
    }

    @Override
    public Map<String, ProcessRelease> latestByDefinition(String tenantId) {
        return jdbc.query("SELECT DISTINCT ON (process_definition_id) " + COLUMNS
                        + " FROM workflow_process_release WHERE tenant_id = ?"
                        + " ORDER BY process_definition_id, performed_at DESC, id DESC",
                        (rs, row) -> map(rs), tenantId).stream()
                .collect(Collectors.toMap(ProcessRelease::processDefinitionId, Function.identity()));
    }

    @Override
    public void deleteByKey(String tenantId, String key) {
        jdbc.update("DELETE FROM workflow_process_release WHERE tenant_id = ? AND process_definition_key = ?",
                tenantId, key);
    }

    private static ProcessRelease map(ResultSet rs) throws SQLException {
        return new ProcessRelease(rs.getLong("id"), rs.getString("tenant_id"),
                rs.getString("process_definition_key"), rs.getString("process_definition_id"),
                rs.getInt("version"), ReleaseAction.valueOf(rs.getString("action")),
                (Integer) rs.getObject("source_version"), rs.getString("xml_sha256"),
                rs.getString("performed_by"), rs.getObject("performed_at", OffsetDateTime.class));
    }
}
```

- [ ] **Step 6: Run the test and see it pass**

Run the command from Step 4. Expected: PASS when Docker is available; SKIPPED without Docker. In that case, run it once on a machine with Docker before merging.

- [ ] **Step 7: Commit**

```bash
git add workflow-agent-service/workflow-engine
git commit -m "feat: persist process drafts and releases"
```

---

### Task 4: Revision error code, size limit and draft inspector

**Files:**
- Modify: `workflow-agent-service/shared-kernel/.../shared/exception/ErrorCode.java`
- Modify: `workflow-agent-service/workflow-boot/src/main/java/.../shared/exception/GlobalExceptionHandler.java`, `GlobalExceptionHandlerTest.java`
- Create: `<main>/config/ProcessDefinitionProperties.java`
- Modify: `workflow-agent-service/workflow-boot/src/main/resources/application.yml`, `workflow-agent-service/.env.example`
- Create: `<main>/process/infrastructure/flowable/BpmnDraftInspector.java`
- Test: `<test>/process/infrastructure/flowable/BpmnDraftInspectorTest.java`

- [ ] **Step 1: Error code**

In `ErrorCode`, after `CONFLICT`:

```java
    DRAFT_REVISION_CONFLICT("DRAFT_REVISION_CONFLICT", "Bản nháp đã được người khác sửa"),
```

In `GlobalExceptionHandler`, change the conflict arm to `case CONFLICT, DRAFT_REVISION_CONFLICT -> HttpStatus.CONFLICT;`. In `GlobalExceptionHandlerTest.businessErrors()`, add `Arguments.of(ErrorCode.DRAFT_REVISION_CONFLICT, HttpStatus.CONFLICT)`.

Run: `mvn -pl workflow-boot -am test -Dtest=GlobalExceptionHandlerTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: PASS.

- [ ] **Step 2: Properties**

```java
package io.github.illuseahashmap.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("workflow.definition")
public class ProcessDefinitionProperties {

    /** Upper bound for a draft or a published BPMN document, in UTF-8 bytes. */
    private int maxBpmnBytes = 2_097_152;

    public int getMaxBpmnBytes() {
        return maxBpmnBytes;
    }

    public void setMaxBpmnBytes(int maxBpmnBytes) {
        this.maxBpmnBytes = maxBpmnBytes;
    }
}
```

In `application.yml`, under `workflow:` next to `script-task:`:

```yaml
  definition:
    max-bpmn-bytes: ${WORKFLOW_DEFINITION_MAX_BPMN_BYTES:2097152}
```

In `.env.example`, add `WORKFLOW_DEFINITION_MAX_BPMN_BYTES=2097152`. `ScriptTaskProperties` is picked up by `@ConfigurationPropertiesScan` in `WorkflowAgentServiceApplication`, and this class is too.

- [ ] **Step 3: Write the failing inspector test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.config.ProcessDefinitionProperties;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

class BpmnDraftInspectorTest {

    static String bpmn(String processes) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                %s
                </definitions>""".formatted(processes);
    }

    private final BpmnDraftInspector inspector = new BpmnDraftInspector(new ProcessDefinitionProperties());

    @Test
    void acceptsOneProcessWithTheKey() {
        inspector.check("order", bpmn("<process id=\"order\" isExecutable=\"true\"/>"));
    }

    @Test
    void rejectsAnInvalidKey() {
        assertThatThrownBy(() -> inspector.check("1order", bpmn("<process id=\"1order\"/>")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("key");
    }

    @Test
    void rejectsAMismatchedProcessId() {
        assertThatThrownBy(() -> inspector.check("order", bpmn("<process id=\"other\"/>")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("process id");
    }

    @Test
    void rejectsSeveralProcesses() {
        assertThatThrownBy(() -> inspector.check("order",
                bpmn("<process id=\"order\"/><process id=\"order2\"/>")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("exactly one");
    }

    @Test
    void rejectsADoctype() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e \"x\">]>" + bpmn("<process id=\"order\"/>")
                .substring(bpmn("").indexOf("<definitions"));
        assertThatThrownBy(() -> inspector.check("order", xml)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsAnOversizedDocument() {
        ProcessDefinitionProperties properties = new ProcessDefinitionProperties();
        properties.setMaxBpmnBytes(64);
        BpmnDraftInspector small = new BpmnDraftInspector(properties);

        assertThatThrownBy(() -> small.check("order", bpmn("<process id=\"order\"/>")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("bytes");
    }

    @Test
    void readsTheProcessName() {
        assertThat(inspector.processName(bpmn("<process id=\"order\" name=\"Đơn hàng\"/>")))
                .contains("Đơn hàng");
    }
}
```

- [ ] **Step 4: Run it and see it fail**

Run: `mvn -pl workflow-engine -am test -Dtest=BpmnDraftInspectorTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation failure.

- [ ] **Step 5: Implement**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.config.ProcessDefinitionProperties;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Checks that must hold for every stored BPMN document, draft or published. */
@Component
public class BpmnDraftInspector {

    static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    private final ProcessDefinitionProperties properties;

    public BpmnDraftInspector(ProcessDefinitionProperties properties) {
        this.properties = properties;
    }

    public void check(String key, String bpmnXml) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw invalid("Process key must match " + KEY.pattern());
        }
        int bytes = bpmnXml.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > properties.getMaxBpmnBytes()) {
            throw invalid("BPMN XML is " + bytes + " bytes, limit is " + properties.getMaxBpmnBytes() + " bytes");
        }
        NodeList processes = parse(bpmnXml).getElementsByTagNameNS(BPMN_NS, "process");
        if (processes.getLength() != 1) {
            throw invalid("BPMN XML must contain exactly one process");
        }
        String id = ((Element) processes.item(0)).getAttribute("id");
        if (!key.equals(id)) {
            throw invalid("BPMN process id must equal the process key");
        }
    }

    public Optional<String> processName(String bpmnXml) {
        NodeList processes = parse(bpmnXml).getElementsByTagNameNS(BPMN_NS, "process");
        if (processes.getLength() == 0) {
            return Optional.empty();
        }
        String name = ((Element) processes.item(0)).getAttribute("name");
        return name.isBlank() ? Optional.empty() : Optional.of(name.trim());
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

- [ ] **Step 6: Run the test and see it pass.** Then commit:

```bash
git add workflow-agent-service
git commit -m "feat: validate stored BPMN identity and size"
```

---

### Task 5: Draft service and endpoints

**Files:**
- Create: `<main>/process/application/ProcessDraftService.java`
- Create: `<main>/process/application/dto/ProcessDraftView.java`, `CreateProcessDraftRequest.java`, `SaveProcessDraftRequest.java`, `DraftVersionRequest.java`, `DraftRevisionRequest.java`
- Create: `<main>/process/infrastructure/flowable/ProcessDefinitionLookup.java`
- Create: `<main>/process/infrastructure/flowable/ProcessDraftServiceImpl.java`
- Modify: `<main>/process/interfaces/rest/WorkflowManagementController.java`
- Test: `<test>/process/infrastructure/flowable/ProcessDraftServiceImplTest.java`, `<test>/process/infrastructure/flowable/InMemoryProcessRepositories.java`

- [ ] **Step 1: DTOs and interface**

```java
package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;

public record ProcessDraftView(
        String processDefinitionKey,
        String name,
        String bpmnXml,
        long revision,
        String updatedBy,
        OffsetDateTime updatedAt,
        Integer latestVersion,
        boolean suspended,
        boolean hasUnpublishedChanges) {
}
```

```java
public record CreateProcessDraftRequest(@NotBlank String processDefinitionKey,
                                        @NotBlank String name,
                                        @NotBlank String bpmnXml) {
}
public record SaveProcessDraftRequest(@NotBlank String name, @NotBlank String bpmnXml, @PositiveOrZero long revision) {
}
public record DraftVersionRequest(@Min(1) int version, @PositiveOrZero long revision) {
}
public record DraftRevisionRequest(@PositiveOrZero long revision) {
}
```

(One file per record, in `process/application/dto`, with `jakarta.validation.constraints` imports.)

```java
package io.github.illuseahashmap.workflow.process.application;

public interface ProcessDraftService {
    ProcessDraftView create(CreateProcessDraftRequest request);
    ProcessDraftView get(String processDefinitionKey);
    ProcessDraftView save(String processDefinitionKey, SaveProcessDraftRequest request);
    ProcessDraftView loadVersion(String processDefinitionKey, DraftVersionRequest request);
    ProcessDraftView discard(String processDefinitionKey, DraftRevisionRequest request);
}
```

- [ ] **Step 2: Shared engine lookup**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Component;

/** Tenant-scoped definition lookups shared by the draft, publish and read services. */
@Component
public class ProcessDefinitionLookup {

    private final RepositoryService repositoryService;

    public ProcessDefinitionLookup(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    public Optional<ProcessDefinition> latest(String tenantId, String key) {
        return Optional.ofNullable(repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId).processDefinitionKey(key).latestVersion().singleResult());
    }

    public Optional<ProcessDefinition> version(String tenantId, String key, int version) {
        return Optional.ofNullable(repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId).processDefinitionKey(key)
                .processDefinitionVersion(version).singleResult());
    }

    public boolean anyVersion(String tenantId, String key) {
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId).processDefinitionKey(key).count() > 0;
    }

    public String xml(ProcessDefinition definition) {
        try (InputStream input = repositoryService.getResourceAsStream(
                definition.getDeploymentId(), definition.getResourceName())) {
            if (input == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "BPMN XML resource does not exist");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "BPMN XML cannot be read");
        }
    }
}
```

- [ ] **Step 3: In-memory repositories for tests**

`<test>/process/infrastructure/flowable/InMemoryProcessRepositories.java` holds two `public static class`es, `Drafts implements ProcessDraftRepository` and `Releases implements ProcessReleaseRepository`. They are backed by a `LinkedHashMap` keyed on `tenantId + "/" + key` (drafts) and an `ArrayList` (releases, with a `long` sequence for `id`). `update` compares the revision, `findForUpdate` delegates to `find`, and `latestByDefinition` keeps the last appended row per id. Keep it under 80 lines. It has no logic beyond the port contract.

- [ ] **Step 4: Write the failing service test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.config.ProcessDefinitionProperties;
import io.github.illuseahashmap.workflow.process.application.dto.CreateProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftRevisionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.SaveProcessDraftRequest;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.Set;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcessDraftServiceImplTest {

    static final String TENANT = "tenant-draft";

    static String xml(String key, String marker) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="s"/><endEvent id="e"/><sequenceFlow id="f" sourceRef="s" targetRef="e"/>
                  </process>
                </definitions>""".formatted(key, marker);
    }

    private static ProcessEngine engine;
    private InMemoryProcessRepositories.Drafts drafts;
    private ProcessDraftServiceImpl service;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:draft-service;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @BeforeEach
    void setUp() {
        drafts = new InMemoryProcessRepositories.Drafts();
        service = new ProcessDraftServiceImpl(drafts,
                new ProcessDefinitionLookup(engine.getRepositoryService()),
                new BpmnDraftInspector(new ProcessDefinitionProperties()),
                () -> new TenantContext.TenantInfo(TENANT, "draft", "Draft"),
                () -> new CurrentPrincipal("USER", "u1", "alice", "Alice", "draft", Set.of(), Set.of()));
    }

    @Test
    void createsAndSavesWithoutDeploying() {
        var created = service.create(new CreateProcessDraftRequest("d1", "D1", xml("d1", "v-a")));
        assertThat(created.revision()).isEqualTo(1);
        assertThat(created.latestVersion()).isNull();

        var saved = service.save("d1", new SaveProcessDraftRequest("D1", xml("d1", "v-b"), 1));

        assertThat(saved.revision()).isEqualTo(2);
        assertThat(saved.updatedBy()).isEqualTo("alice");
        assertThat(engine.getRepositoryService().createDeploymentQuery().deploymentTenantId(TENANT).count()).isZero();
    }

    @Test
    void rejectsAStaleRevision() {
        service.create(new CreateProcessDraftRequest("d2", "D2", xml("d2", "a")));
        service.save("d2", new SaveProcessDraftRequest("D2", xml("d2", "b"), 1));

        assertThatThrownBy(() -> service.save("d2", new SaveProcessDraftRequest("D2", xml("d2", "c"), 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DRAFT_REVISION_CONFLICT));
    }

    @Test
    void rejectsAKeyThatExistsInTheEngine() {
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString("d3.bpmn20.xml", xml("d3", "x")).deploy();

        assertThatThrownBy(() -> service.create(new CreateProcessDraftRequest("d3", "D3", xml("d3", "y"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void tracksUnpublishedChangesAndLoadsVersions() {
        service.create(new CreateProcessDraftRequest("d4", "D4", xml("d4", "one")));
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString("d4.bpmn20.xml", xml("d4", "one")).deploy();
        assertThat(service.get("d4").hasUnpublishedChanges()).isFalse();
        assertThat(service.get("d4").latestVersion()).isEqualTo(1);

        service.save("d4", new SaveProcessDraftRequest("D4", xml("d4", "two"), 1));
        assertThat(service.get("d4").hasUnpublishedChanges()).isTrue();

        var discarded = service.discard("d4", new DraftRevisionRequest(2));
        assertThat(discarded.hasUnpublishedChanges()).isFalse();

        var loaded = service.loadVersion("d4", new DraftVersionRequest(1, discarded.revision()));
        assertThat(loaded.bpmnXml()).contains("one");
    }

    @Test
    void getFallsBackToTheLatestVersionWhenNoDraftExists() {
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString("d5.bpmn20.xml", xml("d5", "legacy")).deploy();

        var view = service.get("d5");

        assertThat(view.revision()).isZero();
        assertThat(view.bpmnXml()).contains("legacy");
    }
}
```

The last test covers keys published by an older build before the migration has run. `get` returns a synthetic view with `revision = 0`, and `save` with `revision = 0` creates the draft.

- [ ] **Step 5: Run it and see it fail**

Run: `mvn -pl workflow-engine -am test -Dtest=ProcessDraftServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation failure.

- [ ] **Step 6: Implement**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.ProcessDraftService;
import io.github.illuseahashmap.workflow.process.application.dto.CreateProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftRevisionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDraftView;
import io.github.illuseahashmap.workflow.process.application.dto.SaveProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository;
import io.github.illuseahashmap.workflow.process.domain.BpmnXmlHash;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProcessDraftServiceImpl implements ProcessDraftService {

    private final ProcessDraftRepository drafts;
    private final ProcessDefinitionLookup lookup;
    private final BpmnDraftInspector inspector;
    private final TenantProvider tenantProvider;
    private final CurrentPrincipalProvider principalProvider;

    public ProcessDraftServiceImpl(ProcessDraftRepository drafts, ProcessDefinitionLookup lookup,
                                   BpmnDraftInspector inspector, TenantProvider tenantProvider,
                                   CurrentPrincipalProvider principalProvider) {
        this.drafts = drafts;
        this.lookup = lookup;
        this.inspector = inspector;
        this.tenantProvider = tenantProvider;
        this.principalProvider = principalProvider;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDraftView create(CreateProcessDraftRequest request) {
        String tenantId = tenantId();
        String key = request.processDefinitionKey().trim();
        inspector.check(key, request.bpmnXml());
        if (lookup.anyVersion(tenantId, key)
                || !drafts.insert(ProcessDraft.of(tenantId, key, request.name().trim(), request.bpmnXml(),
                        1, actor(), now()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "Process key already exists: " + key);
        }
        return get(key);
    }

    @Override
    public ProcessDraftView get(String key) {
        String tenantId = tenantId();
        Optional<ProcessDefinition> latest = lookup.latest(tenantId, key);
        Optional<ProcessDraft> draft = drafts.find(tenantId, key);
        if (draft.isEmpty() && latest.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process draft does not exist");
        }
        String latestHash = latest.map(definition -> BpmnXmlHash.of(lookup.xml(definition))).orElse(null);
        if (draft.isEmpty()) {
            ProcessDefinition definition = latest.get();
            return new ProcessDraftView(key, definition.getName(), lookup.xml(definition), 0, null, null,
                    definition.getVersion(), definition.isSuspended(), false);
        }
        ProcessDraft value = draft.get();
        return new ProcessDraftView(key, value.name(), value.bpmnXml(), value.revision(), value.updatedBy(),
                value.updatedAt(), latest.map(ProcessDefinition::getVersion).orElse(null),
                latest.map(ProcessDefinition::isSuspended).orElse(false),
                latestHash == null || !latestHash.equals(value.xmlSha256()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDraftView save(String key, SaveProcessDraftRequest request) {
        inspector.check(key, request.bpmnXml());
        write(key, request.name().trim(), request.bpmnXml(), request.revision());
        return get(key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDraftView loadVersion(String key, DraftVersionRequest request) {
        ProcessDefinition definition = lookup.version(tenantId(), key, request.version())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process version does not exist"));
        write(key, definition.getName(), lookup.xml(definition), request.revision());
        return get(key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDraftView discard(String key, DraftRevisionRequest request) {
        ProcessDefinition latest = lookup.latest(tenantId(), key)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process has no published version"));
        write(key, latest.getName(), lookup.xml(latest), request.revision());
        return get(key);
    }

    /** Revision 0 means "no draft row yet" for keys that only exist in the engine. */
    private void write(String key, String name, String xml, long expectedRevision) {
        String tenantId = tenantId();
        String safeName = StringUtils.hasText(name) ? name : key;
        Optional<ProcessDraft> current = drafts.find(tenantId, key);
        boolean written = current.isEmpty()
                ? expectedRevision == 0 && lookup.anyVersion(tenantId, key)
                        && drafts.insert(ProcessDraft.of(tenantId, key, safeName, xml, 1, actor(), now()))
                : drafts.update(current.get().next(safeName, xml, actor(), now()), expectedRevision);
        if (!written) {
            if (current.isEmpty() && !lookup.anyVersion(tenantId, key)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Process draft does not exist");
            }
            throw new BusinessException(ErrorCode.DRAFT_REVISION_CONFLICT,
                    "Bản nháp đã được người khác sửa. Hãy tải lại.");
        }
    }

    private String tenantId() {
        return tenantProvider.current().tenantId();
    }

    private String actor() {
        CurrentPrincipal principal = principalProvider.current();
        return StringUtils.hasText(principal.username()) ? principal.username() : principal.principalId();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
```

- [ ] **Step 7: Endpoints**

Add to `WorkflowManagementController`. Inject `ProcessDraftService processDraftService` through the constructor.

```java
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
    public ApiResponse<ProcessDraftView> loadVersion(@PathVariable String key,
                                                     @Valid @RequestBody DraftVersionRequest request) {
        return ApiResponse.ok(processDraftService.loadVersion(key, request));
    }

    @PostMapping("/drafts/{key}/discard")
    @PreAuthorize("hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN')")
    public ApiResponse<ProcessDraftView> discardDraft(@PathVariable String key,
                                                      @Valid @RequestBody DraftRevisionRequest request) {
        return ApiResponse.ok(processDraftService.discard(key, request));
    }
```

- [ ] **Step 8: Run the test and see it pass.** Then commit:

```bash
git add workflow-agent-service
git commit -m "feat: save process drafts without deploying"
```

---

### Task 6: Publish, suspend, resume, rollback

**Files:**
- Modify: `<main>/assignment/application/AssignmentRuleService.java`, `<main>/assignment/application/impl/AssignmentRuleServiceImpl.java`, `<test>/assignment/application/impl/AssignmentRuleServiceImplTest.java`
- Create: `<main>/process/application/ProcessPublishService.java`
- Create: `<main>/process/application/dto/PublishProcessRequest.java`, `PublishProcessResult.java`, `RollbackProcessRequest.java`, `ProcessReleaseView.java`
- Create: `<main>/process/infrastructure/flowable/ProcessDeployer.java` (guards + deployment + release row; shared with the migration in Task 9)
- Create: `<main>/process/infrastructure/flowable/ProcessPublishServiceImpl.java`
- Modify: `<main>/process/infrastructure/flowable/WorkflowDefinitionServiceImpl.java` (`deploy` delegates), `WorkflowDefinitionServiceImplTest.java`
- Modify: `WorkflowManagementController.java`
- Test: `<test>/process/infrastructure/flowable/ProcessPublishServiceImplTest.java`

- [ ] **Step 1: Failing rule-copy test**

Add this test to `AssignmentRuleServiceImplTest` (it uses the class's existing `rule(...)` helper):

```java
    @Test
    void copiesRulesFromAGivenSourceVersion() {
        when(definitionCatalog.findById(TENANT_ID, "expense:1:100"))
                .thenReturn(Optional.of(new ProcessDefinitionCatalog.DefinitionInfo("expense:1:100", "expense", 1)));
        when(definitionCatalog.findById(TENANT_ID, "expense:3:300"))
                .thenReturn(Optional.of(new ProcessDefinitionCatalog.DefinitionInfo("expense:3:300", "expense", 3)));
        when(ruleRepository.findByProcessDefinition(TENANT_ID, "expense:1:100"))
                .thenReturn(List.of(rule(10L, "expense:1:100", 1, "approve", null)));
        when(ruleRepository.findByProcessDefinition(TENANT_ID, "expense:3:300")).thenReturn(List.of());
        when(definitionCatalog.expectedAssignmentType("expense:3:300", "approve"))
                .thenReturn(AssignmentType.ASSIGNEE);

        AssignmentRuleInheritResult result = service.copyRules("expense:1:100", "expense:3:300");

        assertEquals(1, result.copiedCount());
        ArgumentCaptor<NodeAssignmentRule> captor = ArgumentCaptor.forClass(NodeAssignmentRule.class);
        verify(ruleRepository).save(captor.capture());
        assertEquals("expense:3:300", captor.getValue().processDefinitionId());
        assertEquals(3, captor.getValue().version());
    }
```

If `rule(...)` builds an assignment type other than `ASSIGNEE`, stub `expectedAssignmentType` with that value instead.

- [ ] **Step 2: Implement `copyRules`**

In `AssignmentRuleService`, add:

```java
    /** Copies rules from one definition to another of the same key. Missing tasks are skipped, not errors. */
    AssignmentRuleInheritResult copyRules(String sourceProcessDefinitionId, String targetProcessDefinitionId);
```

In `AssignmentRuleServiceImpl`, move the body of `inherit` from `int copied = 0;` through the `return` into `private AssignmentRuleInheritResult copy(String tenantId, ProcessDefinitionCatalog.DefinitionInfo source, ProcessDefinitionCatalog.DefinitionInfo target)`. Then:

```java
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssignmentRuleInheritResult inherit(String processDefinitionId) {
        String tenantId = tenantProvider.current().tenantId();
        ProcessDefinitionCatalog.DefinitionInfo target = requireDefinition(tenantId, processDefinitionId);
        ProcessDefinitionCatalog.DefinitionInfo source = definitionCatalog.findVersions(tenantId, target.key()).stream()
                .filter(candidate -> candidate.version() < target.version())
                .filter(candidate -> ruleRepository.count(tenantId, candidate.id()) > 0)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "No historical assignment rule version exists"));
        return copy(tenantId, source, target);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssignmentRuleInheritResult copyRules(String sourceProcessDefinitionId, String targetProcessDefinitionId) {
        String tenantId = tenantProvider.current().tenantId();
        return copy(tenantId, requireDefinition(tenantId, sourceProcessDefinitionId),
                requireDefinition(tenantId, targetProcessDefinitionId));
    }
```

Run: `mvn -pl workflow-engine -am test -Dtest=AssignmentRuleServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: PASS, including the existing inherit tests.

- [ ] **Step 3: DTOs and interface**

```java
public record PublishProcessRequest(@PositiveOrZero long revision) {
}
public record RollbackProcessRequest(@Min(1) int version) {
}
public record ProcessReleaseView(int version, String action, Integer sourceVersion,
                                 String performedBy, OffsetDateTime performedAt) {
}
public record PublishProcessResult(
        String deploymentId,
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        int version,
        AssignmentRuleInheritResult rules,      // null for the first version
        List<String> newUserTaskIds) {          // user tasks absent from the previous version
}
```

`PublishProcessResult` imports `AssignmentRuleInheritResult` from `assignment.application.dto`. That is application → application, which ArchUnit allows.

```java
package io.github.illuseahashmap.workflow.process.application;

public interface ProcessPublishService {
    PublishProcessResult publish(String processDefinitionKey, PublishProcessRequest request);
    /** CI/SERVICE path: overwrite the draft with this XML, then publish it. */
    PublishProcessResult publishXml(String processDefinitionKey, String name, String bpmnXml);
    PublishProcessResult rollback(String processDefinitionKey, RollbackProcessRequest request);
    ProcessReleaseView suspend(String processDefinitionKey);
    ProcessReleaseView resume(String processDefinitionKey);
    List<ProcessReleaseView> releases(String processDefinitionKey);
}
```

- [ ] **Step 4: Write the failing engine test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritResult;
import io.github.illuseahashmap.workflow.config.ProcessDefinitionProperties;
import io.github.illuseahashmap.workflow.config.ScriptTaskProperties;
import io.github.illuseahashmap.workflow.process.application.AgentBindingDeploymentValidator;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.CreateProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.RollbackProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.SaveProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.port.AgentTaskBindingParser;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
import java.util.Set;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcessPublishServiceImplTest {

    private static final String TENANT = "tenant-publish";

    static String userTaskXml(String key, String... taskIds) {
        StringBuilder tasks = new StringBuilder();
        String previous = "s";
        for (String id : taskIds) {
            tasks.append("<userTask id=\"%s\" flowable:assignee=\"alice\"/>".formatted(id))
                    .append("<sequenceFlow id=\"f_%s\" sourceRef=\"%s\" targetRef=\"%s\"/>".formatted(id, previous, id));
            previous = id;
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="t">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="s"/>%s<endEvent id="e"/>
                    <sequenceFlow id="f_end" sourceRef="%s" targetRef="e"/>
                  </process>
                </definitions>""".formatted(key, key, tasks, previous);
    }

    static String timerStartXml(String key) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s"><timerEventDefinition><timeCycle>R/PT1H</timeCycle></timerEventDefinition></startEvent>
                    <endEvent id="e"/><sequenceFlow id="f" sourceRef="s" targetRef="e"/>
                  </process>
                </definitions>""".formatted(key);
    }

    private static ProcessEngine engine;
    private ProcessDraftServiceImpl drafts;
    private InMemoryProcessRepositories.Releases releases;
    private AssignmentRuleService rules;
    private AgentBindingDeploymentValidator agentValidator;
    private ProcessPublishServiceImpl service;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:publish-service;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @BeforeEach
    void setUp() {
        TenantProvider tenant = () -> new TenantContext.TenantInfo(TENANT, "pub", "Pub");
        CurrentPrincipalProvider principal =
                () -> new CurrentPrincipal("USER", "u1", "alice", "Alice", "pub", Set.of(), Set.of());
        var draftRepository = new InMemoryProcessRepositories.Drafts();
        var lookup = new ProcessDefinitionLookup(engine.getRepositoryService());
        var inspector = new BpmnDraftInspector(new ProcessDefinitionProperties());
        releases = new InMemoryProcessRepositories.Releases();
        rules = mock(AssignmentRuleService.class);
        when(rules.copyRules(anyString(), anyString()))
                .thenAnswer(call -> new AssignmentRuleInheritResult(
                        call.getArgument(0), null, call.getArgument(1), null, 0, 0, List.of()));
        agentValidator = mock(AgentBindingDeploymentValidator.class);
        drafts = new ProcessDraftServiceImpl(draftRepository, lookup, inspector, tenant, principal);
        var deployer = new ProcessDeployer(engine.getRepositoryService(), releases, inspector,
                new ScriptTaskDeploymentGuard(new ScriptTaskProperties()),
                mock(AgentTaskBindingParser.class), agentValidator, principal);
        service = new ProcessPublishServiceImpl(engine.getRepositoryService(), draftRepository, releases,
                lookup, inspector, deployer, rules, mock(WorkflowOperationAuditService.class), tenant, principal);
    }

    @Test
    void publishXmlIsIdempotentForUnchangedXml() {
        var first = service.publishXml("p8", "P8", userTaskXml("p8", "a"));
        var again = service.publishXml("p8", "P8", userTaskXml("p8", "a"));

        assertThat(again.version()).isEqualTo(first.version());
        assertThat(again.processDefinitionId()).isEqualTo(first.processDefinitionId());
    }

    @Test
    void publishDeploysTheDraftAndRecordsARelease() {
        drafts.create(new CreateProcessDraftRequest("p1", "P1", userTaskXml("p1", "a")));

        var result = service.publish("p1", new PublishProcessRequest(1));

        assertThat(result.version()).isEqualTo(1);
        assertThat(result.rules()).isNull();
        assertThat(result.newUserTaskIds()).containsExactly("a");
        assertThat(releases.list(TENANT, "p1")).extracting("action").containsExactly(ReleaseAction.PUBLISH);
        assertThat(drafts.get("p1").hasUnpublishedChanges()).isFalse();
    }

    @Test
    void secondPublishCopiesRulesAndReportsOnlyNewTasks() {
        drafts.create(new CreateProcessDraftRequest("p2", "P2", userTaskXml("p2", "a")));
        var first = service.publish("p2", new PublishProcessRequest(1));
        drafts.save("p2", new SaveProcessDraftRequest("P2", userTaskXml("p2", "a", "b"), 1));

        var second = service.publish("p2", new PublishProcessRequest(2));

        assertThat(second.version()).isEqualTo(2);
        assertThat(second.newUserTaskIds()).containsExactly("b");
        verify(rules).copyRules(first.processDefinitionId(), second.processDefinitionId());
    }

    @Test
    void refusesAStaleOrUnchangedDraft() {
        drafts.create(new CreateProcessDraftRequest("p3", "P3", userTaskXml("p3", "a")));
        service.publish("p3", new PublishProcessRequest(1));

        assertThatThrownBy(() -> service.publish("p3", new PublishProcessRequest(1)))
                .hasMessageContaining("giống");
        assertThatThrownBy(() -> service.publish("p3", new PublishProcessRequest(7)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DRAFT_REVISION_CONFLICT));
    }

    @Test
    void guardFailureDeploysNothing() {
        String script = userTaskXml("p4", "a").replace("<endEvent id=\"e\"/>",
                "<scriptTask id=\"x\" scriptFormat=\"javascript\"><script>1</script></scriptTask><endEvent id=\"e\"/>");
        drafts.create(new CreateProcessDraftRequest("p4", "P4", script));

        assertThatThrownBy(() -> service.publish("p4", new PublishProcessRequest(1)))
                .isInstanceOf(BusinessException.class);
        assertThat(engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionTenantId(TENANT).processDefinitionKey("p4").count()).isZero();
        verify(agentValidator, never()).validate(anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void suspendBlocksNewStartsAndResumeAllowsThem() {
        drafts.create(new CreateProcessDraftRequest("p5", "P5", userTaskXml("p5", "a")));
        var published = service.publish("p5", new PublishProcessRequest(1));

        service.suspend("p5");
        assertThat(engine.getRepositoryService().getProcessDefinition(published.processDefinitionId()).isSuspended())
                .isTrue();
        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceById(published.processDefinitionId()))
                .isInstanceOf(org.flowable.common.engine.api.FlowableException.class);
        assertThatThrownBy(() -> service.suspend("p5")).hasMessageContaining("already");

        service.resume("p5");
        assertThat(engine.getRuntimeService().startProcessInstanceById(published.processDefinitionId())).isNotNull();
        assertThat(releases.list(TENANT, "p5")).extracting("action")
                .containsExactly(ReleaseAction.RESUME, ReleaseAction.SUSPEND, ReleaseAction.PUBLISH);
    }

    @Test
    void rollbackRedeploysTheOldXmlAsANewVersion() {
        drafts.create(new CreateProcessDraftRequest("p6", "P6", userTaskXml("p6", "a")));
        var v1 = service.publish("p6", new PublishProcessRequest(1));
        drafts.save("p6", new SaveProcessDraftRequest("P6", userTaskXml("p6", "a", "b"), 1));
        service.publish("p6", new PublishProcessRequest(2));

        var v3 = service.rollback("p6", new RollbackProcessRequest(1));

        assertThat(v3.version()).isEqualTo(3);
        assertThat(engine.getRepositoryService().getBpmnModel(v3.processDefinitionId())
                .getMainProcess().getFlowElement("b")).isNull();
        verify(rules).copyRules(v1.processDefinitionId(), v3.processDefinitionId());
        assertThat(releases.list(TENANT, "p6").getFirst().sourceVersion()).isEqualTo(1);
    }

    @Test
    void timerStartRunsOnlyAfterPublish() {
        drafts.create(new CreateProcessDraftRequest("p7", "P7", timerStartXml("p7")));
        assertThat(engine.getManagementService().createTimerJobQuery().jobTenantId(TENANT).count()).isZero();

        service.publish("p7", new PublishProcessRequest(1));
        drafts.save("p7", new SaveProcessDraftRequest("P7", timerStartXml("p7").replace("PT1H", "PT2H"), 1));

        assertThat(engine.getManagementService().createTimerJobQuery().jobTenantId(TENANT).count()).isEqualTo(1);
        assertThat(engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionTenantId(TENANT).processDefinitionKey("p7").count()).isEqualTo(1);
    }
}
```

`guardFailureDeploysNothing` depends on the script guard rejecting script tasks while `workflow.script-task.enabled=false`, which is the default in `ScriptTaskProperties`.

- [ ] **Step 5: Run it and see it fail**

Run: `mvn -pl workflow-engine -am test -Dtest=ProcessPublishServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation failure.

- [ ] **Step 6: Implement**

`ProcessDeployer` is a separate bean with public methods. The migration (Task 9) needs it too, and calling package-private methods through the transactional CGLIB proxy of the publish service would run them on an instance whose fields are not set.

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.AgentBindingDeploymentValidator;
import io.github.illuseahashmap.workflow.process.application.port.AgentTaskBindingParser;
import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** The only place that calls createDeployment. Callers own the transaction. */
@Component
public class ProcessDeployer {

    private final RepositoryService repositoryService;
    private final ProcessReleaseRepository releases;
    private final BpmnDraftInspector inspector;
    private final ScriptTaskDeploymentGuard scriptTaskDeploymentGuard;
    private final AgentTaskBindingParser agentTaskBindingParser;
    private final AgentBindingDeploymentValidator agentBindingDeploymentValidator;
    private final CurrentPrincipalProvider principalProvider;

    public ProcessDeployer(RepositoryService repositoryService, ProcessReleaseRepository releases,
                           BpmnDraftInspector inspector, ScriptTaskDeploymentGuard scriptTaskDeploymentGuard,
                           AgentTaskBindingParser agentTaskBindingParser,
                           AgentBindingDeploymentValidator agentBindingDeploymentValidator,
                           CurrentPrincipalProvider principalProvider) {
        this.repositoryService = repositoryService;
        this.releases = releases;
        this.inspector = inspector;
        this.scriptTaskDeploymentGuard = scriptTaskDeploymentGuard;
        this.agentTaskBindingParser = agentTaskBindingParser;
        this.agentBindingDeploymentValidator = agentBindingDeploymentValidator;
        this.principalProvider = principalProvider;
    }

    /** Every guard, then the deployment. Later plans add their guards here, in order. */
    public ProcessDefinition deployChecked(TenantContext.TenantInfo tenant, String key, String name, String xml) {
        inspector.check(key, xml);
        scriptTaskDeploymentGuard.check(xml);
        agentBindingDeploymentValidator.validate(tenant.tenantCode(), agentTaskBindingParser.parse(xml));
        return deployUnchecked(tenant, key, name, xml);
    }

    /** No guards. Only the migration calls this directly, to keep what is already running. */
    public ProcessDefinition deployUnchecked(TenantContext.TenantInfo tenant, String key, String name, String xml) {
        Deployment deployment = repositoryService.createDeployment()
                .name(StringUtils.hasText(name) ? name : key)
                .key(key)
                .tenantId(tenant.tenantId())
                .addString(key + ".bpmn20.xml", xml)
                .deploy();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .processDefinitionTenantId(tenant.tenantId())
                .singleResult();
        if (definition == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "No process definition was deployed");
        }
        if (!key.equals(definition.getKey())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "BPMN process id must equal processDefinitionKey");
        }
        return definition;
    }

    public ProcessRelease record(TenantContext.TenantInfo tenant, ProcessDefinition definition,
                                 ReleaseAction action, Integer sourceVersion, String hash) {
        CurrentPrincipal principal = principalProvider.current();
        String actor = StringUtils.hasText(principal.username()) ? principal.username() : principal.principalId();
        ProcessRelease release = new ProcessRelease(null, tenant.tenantId(), definition.getKey(),
                definition.getId(), definition.getVersion(), action, sourceVersion, hash, actor,
                OffsetDateTime.now(ZoneOffset.UTC));
        releases.append(release);
        return release;
    }
}
```

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritResult;
import io.github.illuseahashmap.workflow.process.application.ProcessPublishService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessReleaseView;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.RollbackProcessRequest;
import io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository;
import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.process.domain.BpmnXmlHash;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProcessPublishServiceImpl implements ProcessPublishService {

    private final RepositoryService repositoryService;
    private final ProcessDraftRepository drafts;
    private final ProcessReleaseRepository releases;
    private final ProcessDefinitionLookup lookup;
    private final BpmnDraftInspector inspector;
    private final ProcessDeployer deployer;
    private final AssignmentRuleService assignmentRuleService;
    private final WorkflowOperationAuditService auditService;
    private final TenantProvider tenantProvider;
    private final CurrentPrincipalProvider principalProvider;

    public ProcessPublishServiceImpl(RepositoryService repositoryService,
                                     ProcessDraftRepository drafts, ProcessReleaseRepository releases,
                                     ProcessDefinitionLookup lookup, BpmnDraftInspector inspector,
                                     ProcessDeployer deployer, AssignmentRuleService assignmentRuleService,
                                     WorkflowOperationAuditService auditService,
                                     TenantProvider tenantProvider, CurrentPrincipalProvider principalProvider) {
        this.repositoryService = repositoryService;
        this.drafts = drafts;
        this.releases = releases;
        this.lookup = lookup;
        this.inspector = inspector;
        this.deployer = deployer;
        this.assignmentRuleService = assignmentRuleService;
        this.auditService = auditService;
        this.tenantProvider = tenantProvider;
        this.principalProvider = principalProvider;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PublishProcessResult publish(String key, PublishProcessRequest request) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        ProcessDraft draft = drafts.findForUpdate(tenant.tenantId(), key)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process draft does not exist"));
        if (draft.revision() != request.revision()) {
            throw new BusinessException(ErrorCode.DRAFT_REVISION_CONFLICT,
                    "Bản nháp đã được người khác sửa. Hãy tải lại.");
        }
        ProcessDefinition previous = lookup.latest(tenant.tenantId(), key).orElse(null);
        if (previous != null && !previous.isSuspended()
                && BpmnXmlHash.of(lookup.xml(previous)).equals(draft.xmlSha256())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Bản nháp giống bản đang chạy, không có gì để phát hành");
        }
        ProcessDefinition definition = deployer.deployChecked(tenant, key, draft.name(), draft.bpmnXml());
        AssignmentRuleInheritResult rules = previous == null
                ? null : assignmentRuleService.copyRules(previous.getId(), definition.getId());
        deployer.record(tenant, definition, ReleaseAction.PUBLISH, null, draft.xmlSha256());
        auditService.record("PROCESS_DEFINITION_PUBLISHED", null, key, null,
                "version=" + definition.getVersion(), null, "PUBLISHED", null);
        return result(definition, rules, previous);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PublishProcessResult publishXml(String key, String name, String bpmnXml) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        inspector.check(key, bpmnXml);
        ProcessDraft current = drafts.findForUpdate(tenant.tenantId(), key).orElse(null);
        ProcessDraft next = current == null
                ? ProcessDraft.of(tenant.tenantId(), key, name, bpmnXml, 1, actor(), now())
                : current.next(name, bpmnXml, actor(), now());
        boolean written = current == null ? drafts.insert(next) : drafts.update(next, current.revision());
        if (!written) {
            throw new BusinessException(ErrorCode.DRAFT_REVISION_CONFLICT, "Process draft changed concurrently");
        }
        // CI may redeploy the same XML. Return the running version instead of failing.
        ProcessDefinition latest = lookup.latest(tenant.tenantId(), key).orElse(null);
        if (latest != null && !latest.isSuspended()
                && BpmnXmlHash.of(lookup.xml(latest)).equals(next.xmlSha256())) {
            return new PublishProcessResult(latest.getDeploymentId(), latest.getId(), latest.getKey(),
                    latest.getName(), latest.getVersion(), null, List.of());
        }
        return publish(key, new PublishProcessRequest(next.revision()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PublishProcessResult rollback(String key, RollbackProcessRequest request) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        ProcessDefinition latest = requireLatest(tenant.tenantId(), key);
        ProcessDefinition source = lookup.version(tenant.tenantId(), key, request.version())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process version does not exist"));
        if (source.getId().equals(latest.getId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "This is already the latest version");
        }
        String xml = lookup.xml(source);
        ProcessDefinition definition = deployer.deployChecked(tenant, key, source.getName(), xml);
        AssignmentRuleInheritResult rules = assignmentRuleService.copyRules(source.getId(), definition.getId());
        deployer.record(tenant, definition, ReleaseAction.ROLLBACK, source.getVersion(), BpmnXmlHash.of(xml));
        auditService.record("PROCESS_DEFINITION_ROLLED_BACK", null, key, null,
                "version=" + definition.getVersion() + ", source=" + source.getVersion(), null, "PUBLISHED", null);
        return result(definition, rules, latest);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessReleaseView suspend(String key) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        ProcessDefinition latest = requireLatest(tenant.tenantId(), key);
        if (latest.isSuspended()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Process is already suspended");
        }
        repositoryService.suspendProcessDefinitionById(latest.getId(), false, null);
        auditService.record("PROCESS_DEFINITION_SUSPENDED", null, key, null,
                "version=" + latest.getVersion(), "PUBLISHED", "SUSPENDED", null);
        return view(deployer.record(tenant, latest, ReleaseAction.SUSPEND, null, BpmnXmlHash.of(lookup.xml(latest))));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessReleaseView resume(String key) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        ProcessDefinition latest = requireLatest(tenant.tenantId(), key);
        if (!latest.isSuspended()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Process is not suspended");
        }
        repositoryService.activateProcessDefinitionById(latest.getId(), false, null);
        auditService.record("PROCESS_DEFINITION_RESUMED", null, key, null,
                "version=" + latest.getVersion(), "SUSPENDED", "PUBLISHED", null);
        return view(deployer.record(tenant, latest, ReleaseAction.RESUME, null, BpmnXmlHash.of(lookup.xml(latest))));
    }

    @Override
    public List<ProcessReleaseView> releases(String key) {
        return releases.list(tenantProvider.current().tenantId(), key).stream().map(this::view).toList();
    }

    private PublishProcessResult result(ProcessDefinition definition, AssignmentRuleInheritResult rules,
                                        ProcessDefinition previous) {
        Set<String> added = userTaskIds(definition.getId());
        if (previous != null) {
            added.removeAll(userTaskIds(previous.getId()));
        }
        return new PublishProcessResult(definition.getDeploymentId(), definition.getId(), definition.getKey(),
                definition.getName(), definition.getVersion(), rules, List.copyOf(added));
    }

    private Set<String> userTaskIds(String definitionId) {
        Set<String> ids = new LinkedHashSet<>();
        repositoryService.getBpmnModel(definitionId).getMainProcess()
                .findFlowElementsOfType(UserTask.class, true)
                .forEach(task -> ids.add(task.getId()));
        return ids;
    }

    private ProcessDefinition requireLatest(String tenantId, String key) {
        return lookup.latest(tenantId, key)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process has no published version"));
    }

    private ProcessReleaseView view(ProcessRelease release) {
        return new ProcessReleaseView(release.version(), release.action().name(), release.sourceVersion(),
                release.performedBy(), release.performedAt());
    }

    private String actor() {
        CurrentPrincipal principal = principalProvider.current();
        return StringUtils.hasText(principal.username()) ? principal.username() : principal.principalId();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
```

- [ ] **Step 7: Route `POST /deploy` through publish**

In `WorkflowDefinitionServiceImpl`:
- Replace the constructor dependencies `AgentTaskBindingParser`, `AgentBindingDeploymentValidator` and `ScriptTaskDeploymentGuard` with `ProcessPublishService publishService`.
- Change the body of `deploy` to:

```java
    @Override
    public DeployProcessResult deploy(DeployProcessRequest request) {
        PublishProcessResult result = publishService.publishXml(
                request.processDefinitionKey().trim(), request.processDefinitionName().trim(), request.bpmnXml());
        return new DeployProcessResult(result.deploymentId(), result.processDefinitionId(),
                result.processDefinitionKey(), result.processDefinitionName(), result.version());
    }
```

Rewrite `WorkflowDefinitionServiceImplTest.rejectsScriptTasksBeforeTouchingTheEngine` so it asserts that `deploy` delegates to `publishService.publishXml` with trimmed arguments. The script guard ordering is now covered by `ProcessPublishServiceImplTest.guardFailureDeploysNothing`.

- [ ] **Step 8: Endpoints**

Inject `ProcessPublishService processPublishService` into `WorkflowManagementController`:

```java
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
```

`/{key}/...` cannot collide with the existing literal paths, because those are matched first and none of them have a second path segment from this set.

- [ ] **Step 9: Run the tests and see them pass**

Run: `mvn -pl workflow-engine -am test -Dtest='ProcessPublishServiceImplTest,ProcessDraftServiceImplTest,WorkflowDefinitionServiceImplTest,AssignmentRuleServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add workflow-agent-service
git commit -m "feat: publish, suspend, resume and roll back processes"
```

---

### Task 7: Readers use "latest and not suspended"

**Files:**
- Modify: `<main>/process/application/WorkflowDefinitionService.java`, `<main>/process/infrastructure/flowable/WorkflowDefinitionServiceImpl.java`
- Modify: `<main>/process/infrastructure/flowable/WorkflowTaskOperationSupport.java`
- Modify: `<main>/process/infrastructure/flowable/WorkflowDefinitionReadService.java`
- Modify: `<main>/process/application/dto/ProcessDefinitionSummaryView.java`
- Test: `<test>/process/infrastructure/flowable/RunningDefinitionTest.java` (H2 engine)

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.illuseahashmap.workflow.process.application.ProcessPublishService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RunningDefinitionTest {

    private static final String TENANT = "tenant-running";
    private static ProcessEngine engine;
    private static WorkflowDefinitionServiceImpl definitions;
    private static WorkflowTaskOperationSupport support;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:running;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
        var tenant = (io.github.illuseahashmap.workflow.shared.context.TenantProvider)
                () -> new TenantContext.TenantInfo(TENANT, "run", "Run");
        definitions = new WorkflowDefinitionServiceImpl(engine.getRepositoryService(), tenant,
                mock(ProcessPublishService.class),
                mock(io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository.class),
                mock(io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository.class),
                new ProcessDefinitionLookup(engine.getRepositoryService()));
        support = new WorkflowTaskOperationSupport(engine.getRuntimeService(), engine.getTaskService(),
                engine.getRepositoryService(), definitions, null, null, tenant);
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static ProcessDefinition deploy(String key) {
        String xml = ProcessDraftServiceImplTest.xml(key, key);
        var deployment = engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString(key + ".bpmn20.xml", xml).deploy();
        return engine.getRepositoryService().createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
    }

    @Test
    void startByKeyUsesLatestAndRejectsSuspended() {
        deploy("r1");
        ProcessDefinition v2 = deploy("r1");

        assertThat(support.resolveProcessDefinitionId("r1", null, TENANT)).isEqualTo(v2.getId());

        engine.getRepositoryService().suspendProcessDefinitionById(v2.getId(), false, null);
        assertThatThrownBy(() -> support.resolveProcessDefinitionId("r1", null, TENANT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void startByIdAcceptsOnlyTheLatest() {
        ProcessDefinition v1 = deploy("r2");
        ProcessDefinition v2 = deploy("r2");

        assertThat(support.resolveProcessDefinitionId("r2", v2.getId(), TENANT)).isEqualTo(v2.getId());
        assertThatThrownBy(() -> support.resolveProcessDefinitionId("r2", v1.getId(), TENANT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void activeFlagFollowsLatestAndSuspension() {
        deploy("r3");
        ProcessDefinition v2 = deploy("r3");

        assertThat(definitions.list("r3")).extracting("version", "active")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2, true),
                        org.assertj.core.groups.Tuple.tuple(1, false));
        engine.getRepositoryService().suspendProcessDefinitionById(v2.getId(), false, null);
        assertThat(definitions.list("r3").getFirst().active()).isFalse();
    }
}
```

Match the constructor arguments of `WorkflowTaskOperationSupport` to the real signature (`runtimeService, taskService, repositoryService, definitionService, taskViewAssembler, participantDirectory, tenantProvider`). Pass `null` for collaborators that `resolveProcessDefinitionId` does not touch.

- [ ] **Step 2: Run it and see it fail.** Command: `mvn -pl workflow-engine -am test -Dtest=RunningDefinitionTest -Dsurefire.failIfNoSpecifiedTests=false`.

- [ ] **Step 3: `WorkflowDefinitionService`**

Add `String runningProcessDefinitionId(String processDefinitionKey);`. Keep `activate`/`getActiveVersion` for the transition period.

The final constructor of `WorkflowDefinitionServiceImpl` is `(RepositoryService, TenantProvider, ProcessPublishService, ProcessReleaseRepository, ProcessDraftRepository, ProcessDefinitionLookup)`. `JdbcTemplate`, `CurrentPrincipalProvider` and `WorkflowOperationAuditService` are no longer needed here, because activation now goes through the publish service, which audits. Remove them and update `WorkflowDefinitionServiceImplTest` to the same signature.

```java
    @Override
    public String runningProcessDefinitionId(String key) {
        ProcessDefinition latest = lookup.latest(tenantProvider.current().tenantId(), key)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist"));
        if (latest.isSuspended()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Quy trình đã ngừng phát hành");
        }
        return latest.getId();
    }

    @Override
    @Deprecated
    public ActiveProcessVersionResult activate(ActivateProcessVersionRequest request) {
        LOGGER.warn("Deprecated /definition/activate called for {} v{}",
                request.processDefinitionKey(), request.version());
        String tenantId = tenantProvider.current().tenantId();
        ProcessDefinition latest = lookup.latest(tenantId, request.processDefinitionKey())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process definition version does not exist"));
        if (request.version() == latest.getVersion()) {
            if (latest.isSuspended()) {
                publishService.resume(request.processDefinitionKey());
            }
        } else {
            publishService.rollback(request.processDefinitionKey(), new RollbackProcessRequest(request.version()));
        }
        return getActiveVersion(request.processDefinitionKey());
    }

    @Override
    public ActiveProcessVersionResult getActiveVersion(String key) {
        String tenantId = tenantProvider.current().tenantId();
        ProcessDefinition latest = lookup.latest(tenantId, key)
                .filter(definition -> !definition.isSuspended())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Active process definition version does not exist"));
        ProcessRelease release = releases.latestByDefinition(tenantId).get(latest.getId());
        return new ActiveProcessVersionResult(tenantId, key, latest.getId(), latest.getVersion(),
                release == null ? null : release.performedBy(),
                release == null ? getDeploymentTime(latest.getDeploymentId()) : release.performedAt());
    }

    @Override
    public boolean exists(String key) {
        String tenantId = tenantProvider.current().tenantId();
        return lookup.anyVersion(tenantId, key) || drafts.find(tenantId, key).isPresent();
    }
```

Replace `isActive(definition)` (the SQL on `workflow_active_version`) with:

```java
    private boolean isActive(ProcessDefinition definition) {
        return !definition.isSuspended() && lookup.latest(definition.getTenantId(), definition.getKey())
                .map(latest -> latest.getId().equals(definition.getId())).orElse(false);
    }
```

Remove `mapActiveVersion`. Add `private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowDefinitionServiceImpl.class);`.

- [ ] **Step 4: `WorkflowTaskOperationSupport.resolveProcessDefinitionId`**

Replace the id branch and the final return:

```java
        if (StringUtils.hasText(requestedDefinitionId)) {
            String definitionId = requestedDefinitionId.trim();
            var definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionId(definitionId).singleResult();
            if (definition == null || !Objects.equals(tenantId, definition.getTenantId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
            }
            if (!Objects.equals(normalizedKey, definition.getKey())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "Process definition id does not match the requested process definition key");
            }
            String running = definitionService.runningProcessDefinitionId(normalizedKey);
            if (!running.equals(definitionId)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "Only the published version can be started: " + normalizedKey);
            }
            return definitionId;
        }
        return definitionService.runningProcessDefinitionId(normalizedKey);
```

- [ ] **Step 5: `WorkflowDefinitionReadService`**

- Inject `ProcessDraftRepository` and `ProcessReleaseRepository`. Remove `activeVersions()` and `ActiveVersionRow`.
- Add `boolean hasUnpublishedChanges` as the last component of `ProcessDefinitionSummaryView`.
- `page(...)`:
  1. Load the latest definitions as today.
  2. Load `drafts.listHeaders(tenantId)` and `releases.latestByDefinition(tenantId)`.
  3. Build each summary from the latest definition:
     - `activeVersion` is the latest version when not suspended, otherwise `null` (same for `activeProcessDefinitionId` and `activeDeploymentId`)
     - `activeUpdateTime` is the release `performedAt`
     - `publishStatus` is `"suspended"` or `"published"`
     - `hasUnpublishedChanges` is true when the draft header's hash differs from the release hash
  4. For draft headers whose key has no definition, add a summary with `processDefinitionId = null`, `latestVersion = 0`, `publishStatus = "draft_only"` and `latestDeployTime = updatedAt`.
  5. `normalizeStatus` accepts `all`, `published`, `suspended`, `draft_only`, and `unpublished` (meaning "not `published`", kept for old clients).
- `resolve(...)` without a version or id: use `lookup.latest`, suspended or not, so that a stopped process can still be viewed.
- `diagram(...)`: `active = !definition.isSuspended() && definition is latest`.

Add a unit test `WorkflowDefinitionReadServiceTest.pageMergesDraftOnlyKeys` with mocked repositories and an H2 engine, following the setup in Step 1.

- [ ] **Step 6: Run the tests and grep**

Run: `mvn -pl workflow-engine -am test`. Expected: PASS.

Run: `grep -rn "workflow_active_version" workflow-agent-service --include=*.java | grep -v /test/`. Expected: only `DELETE` statements in `WorkflowAdministrationServiceImpl` (legacy cleanup) and the read in `DraftPublishMigration` (Task 9).

- [ ] **Step 7: Commit**

```bash
git add workflow-agent-service
git commit -m "feat: treat the latest non-suspended definition as running"
```

---

### Task 8: Delete rules

**Files:**
- Modify: `<main>/process/infrastructure/flowable/WorkflowAdministrationServiceImpl.java`
- Test: `<test>/process/infrastructure/flowable/WorkflowAdministrationDeleteTest.java` (H2 engine, mocked rule service and repositories)

- [ ] **Step 1: Failing tests**
  - `deleteVersionRejectsTheLatest`: deploy v1 and v2; deleting v2 → `BAD_REQUEST` with "ngừng phát hành".
  - `deleteVersionRejectsRunningInstances`: deploy v1 and v2, start an instance on v1; deleting v1 → `CONFLICT`.
  - `deleteVersionRemovesAnIdleOldVersion`: deploy v1 and v2; deleting v1 → the v1 query count is 0, and `assignmentRuleService.deleteByProcessDefinition(v1Id)` is verified.
  - `deleteKeyRejectsRunningInstances` and `deleteKeyRemovesDraftAndReleases`: verify `drafts.delete` and `releases.deleteByKey`.

- [ ] **Step 2: Implement**

At the top of `deleteProcessDefinitionVersion`, after the definition lookup:

```java
        ProcessDefinition latest = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId).processDefinitionKey(processDefinitionKey)
                .latestVersion().singleResult();
        if (latest != null && latest.getId().equals(definition.getId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Không xoá được bản mới nhất. Hãy ngừng phát hành nếu không muốn dùng nữa.");
        }
        long running = runtimeService.createProcessInstanceQuery()
                .processInstanceTenantId(tenantId).processDefinitionId(definition.getId()).count();
        if (running > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Còn " + running + " instance đang chạy trên version này");
        }
```

At the top of `deleteProcessDefinitions`, after the empty check:

```java
        long running = runtimeService.createProcessInstanceQuery()
                .processInstanceTenantId(tenantId).processDefinitionKey(processDefinitionKey).count();
        if (running > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "Còn " + running + " instance đang chạy");
        }
```

and after the deployments are deleted, call `drafts.delete(tenantId, key)` and `releases.deleteByKey(tenantId, key)`. Also allow deleting a key that has only a draft: if `definitions.isEmpty()` and a draft exists, delete the draft and return instead of throwing `NOT_FOUND`.

- [ ] **Step 3: Run the tests, then commit**

```bash
git add workflow-agent-service
git commit -m "feat: guard version and process deletion"
```

---

### Task 9: Migration command

**Files:**
- Create: `<main>/process/application/dto/DraftPublishMigrationResult.java`
- Create: `<main>/process/infrastructure/flowable/DraftPublishMigration.java`
- Modify: `WorkflowManagementController.java`
- Test: `<test>/process/infrastructure/flowable/DraftPublishMigrationTest.java`

- [ ] **Step 1: Result type**

```java
package io.github.illuseahashmap.workflow.process.application.dto;

import java.util.List;

public record DraftPublishMigrationResult(boolean dryRun, List<Item> items) {

    public enum Scenario { ALREADY_MIGRATED, ACTIVE_IS_LATEST, DRAFT_AHEAD, NEVER_PUBLISHED }

    public record Item(String processDefinitionKey, Scenario scenario, String action,
                       Integer newVersion, String error) {
    }
}
```

- [ ] **Step 2: Write the failing test**

Use an H2 engine with URL `jdbc:h2:mem:migration;DB_CLOSE_DELAY=-1`. Create a `JdbcTemplate` on a `DriverManagerDataSource` with the same URL, and `CREATE TABLE workflow_active_version (tenant_id VARCHAR(64), process_definition_key VARCHAR(128), process_definition_id VARCHAR(128), version INT, activated_by VARCHAR(128), activated_at TIMESTAMP)`. Use the in-memory repositories, a mocked `AssignmentRuleService` and a real `ProcessDeployer` built as in Task 6. Set up these keys:
- `m1`: v1, active = v1 → `ACTIVE_IS_LATEST`. Draft equals the v1 XML, there is no new version, and there is one `MIGRATED` release.
- `m2`: v1 (marker "live"), v2 (marker "wip"), active = v1 → `DRAFT_AHEAD`. A v3 exists whose XML contains "live", the draft contains "wip", `copyRules(v1, v3)` is verified, and the release has `sourceVersion = 1`.
- `m3`: v1, no active row → `NEVER_PUBLISHED`. v1 is suspended and the draft exists.

Then:
- `dryRun=true` first: nothing changes, and the scenarios are reported.
- Run again with `dryRun=false`, then a second time: every key is `ALREADY_MIGRATED`, and the version counts do not change.

- [ ] **Step 3: Implement**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

// imports omitted

/** One-off conversion of the current tenant from the active-version table to draft/publish. */
@Component
public class DraftPublishMigration {

    private final RepositoryService repositoryService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate perKey;
    private final ProcessDraftRepository drafts;
    private final ProcessReleaseRepository releases;
    private final ProcessDefinitionLookup lookup;
    private final ProcessDeployer publisher;
    private final AssignmentRuleService assignmentRuleService;
    private final TenantProvider tenantProvider;

    public DraftPublishMigration(RepositoryService repositoryService, JdbcTemplate jdbc,
                                 PlatformTransactionManager transactionManager,
                                 ProcessDraftRepository drafts, ProcessReleaseRepository releases,
                                 ProcessDefinitionLookup lookup, ProcessDeployer publisher,
                                 AssignmentRuleService assignmentRuleService, TenantProvider tenantProvider) {
        // assign fields
        this.perKey = new TransactionTemplate(transactionManager);
        this.perKey.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public DraftPublishMigrationResult run(boolean dryRun) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        List<String> keys = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenant.tenantId()).latestVersion().list().stream()
                .map(ProcessDefinition::getKey).sorted().toList();
        List<DraftPublishMigrationResult.Item> items = new ArrayList<>();
        for (String key : keys) {
            try {
                items.add(dryRun ? plan(tenant, key) : perKey.execute(status -> migrate(tenant, key)));
            } catch (RuntimeException exception) {
                items.add(new DraftPublishMigrationResult.Item(key, null, "FAILED", null, exception.getMessage()));
            }
        }
        return new DraftPublishMigrationResult(dryRun, items);
    }

    private DraftPublishMigrationResult.Item plan(TenantContext.TenantInfo tenant, String key) {
        return new DraftPublishMigrationResult.Item(key, scenario(tenant, key), "PLANNED", null, null);
    }

    private DraftPublishMigrationResult.Item migrate(TenantContext.TenantInfo tenant, String key) {
        DraftPublishMigrationResult.Scenario scenario = scenario(tenant, key);
        ProcessDefinition latest = lookup.latest(tenant.tenantId(), key).orElseThrow();
        String latestXml = lookup.xml(latest);
        Integer newVersion = null;
        switch (scenario) {
            case ALREADY_MIGRATED -> {
                return new DraftPublishMigrationResult.Item(key, scenario, "SKIPPED", null, null);
            }
            case ACTIVE_IS_LATEST -> publisher.record(tenant, latest, ReleaseAction.MIGRATED, null,
                    BpmnXmlHash.of(latestXml));
            case DRAFT_AHEAD -> {
                ProcessDefinition active = repositoryService.getProcessDefinition(activeId(tenant, key).orElseThrow());
                String activeXml = lookup.xml(active);
                ProcessDefinition redeployed = publisher.deployUnchecked(tenant, key, active.getName(), activeXml);
                assignmentRuleService.copyRules(active.getId(), redeployed.getId());
                publisher.record(tenant, redeployed, ReleaseAction.MIGRATED, active.getVersion(),
                        BpmnXmlHash.of(activeXml));
                newVersion = redeployed.getVersion();
            }
            case NEVER_PUBLISHED -> {
                repositoryService.createProcessDefinitionQuery().processDefinitionTenantId(tenant.tenantId())
                        .processDefinitionKey(key).active().list()
                        .forEach(definition -> repositoryService.suspendProcessDefinitionById(
                                definition.getId(), false, null));
                publisher.record(tenant, latest, ReleaseAction.MIGRATED, null, BpmnXmlHash.of(latestXml));
            }
        }
        if (drafts.find(tenant.tenantId(), key).isEmpty()) {
            drafts.insert(ProcessDraft.of(tenant.tenantId(), key,
                    latest.getName() == null ? key : latest.getName(), latestXml, 1, "migration",
                    OffsetDateTime.now(ZoneOffset.UTC)));
        }
        return new DraftPublishMigrationResult.Item(key, scenario, "MIGRATED", newVersion, null);
    }

    private DraftPublishMigrationResult.Scenario scenario(TenantContext.TenantInfo tenant, String key) {
        if (releases.hasAny(tenant.tenantId(), key)) {
            return DraftPublishMigrationResult.Scenario.ALREADY_MIGRATED;
        }
        String latestId = lookup.latest(tenant.tenantId(), key).orElseThrow().getId();
        return activeId(tenant, key)
                .map(id -> id.equals(latestId)
                        ? DraftPublishMigrationResult.Scenario.ACTIVE_IS_LATEST
                        : DraftPublishMigrationResult.Scenario.DRAFT_AHEAD)
                .orElse(DraftPublishMigrationResult.Scenario.NEVER_PUBLISHED);
    }

    private Optional<String> activeId(TenantContext.TenantInfo tenant, String key) {
        return jdbc.queryForList("""
                SELECT process_definition_id FROM workflow_active_version
                WHERE tenant_id = ? AND process_definition_key = ?
                """, String.class, tenant.tenantId(), key).stream().findFirst();
    }
}
```

Notes:
- In the `DRAFT_AHEAD` case, the old active XML is redeployed **without** guards (`deployUnchecked`), so that a model that is already live is never refused because guard rules got stricter later.
- A migrated draft has `revision = 1`, `updated_by = "migration"`.
- `deployUnchecked` and `record` are public methods of `ProcessDeployer` (Task 6). In the test, build a real `ProcessDeployer` the same way as in `ProcessPublishServiceImplTest`.

- [ ] **Step 4: Endpoint**

```java
    @PostMapping("/migrations/draft-publish")
    @PreAuthorize("hasRole('WF_PLATFORM_ADMIN')")
    public ApiResponse<DraftPublishMigrationResult> migrateDraftPublish(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return ApiResponse.ok(draftPublishMigration.run(dryRun));
    }
```

The controller sits in `interfaces.rest`, and the migration is an infrastructure `@Component`. Check that ArchUnit has no rule forbidding `interfaces.rest` → `infrastructure`. If it does, add a `DraftPublishMigrationService` interface in `process/application` and have the component implement it.

`dryRun` defaults to `true`, so an accidental call changes nothing.

- [ ] **Step 5: Run the tests, then commit**

```bash
git add workflow-agent-service
git commit -m "feat: migrate tenants to draft and publish"
```

---

### Task 10: Web API, types and status helper

**Files:**
- Modify: `workflow-agent-web/src/features/process-definition/types.ts`, `api.ts`
- Modify: `workflow-agent-web/src/utils/status.ts`
- Create: `workflow-agent-web/src/features/process-definition/draftState.ts`, `draftState.spec.ts`

- [ ] **Step 1: Types**

In `types.ts`:

```ts
export type PublishStatus = 'all' | 'published' | 'suspended' | 'draft_only'

export interface ProcessDefinitionSummary {
  // existing fields …
  processDefinitionId: string | null
  hasUnpublishedChanges: boolean
}

export interface ProcessDraft {
  processDefinitionKey: string
  name: string
  bpmnXml: string
  revision: number
  updatedBy?: string
  updatedAt?: string
  latestVersion?: number
  suspended: boolean
  hasUnpublishedChanges: boolean
}

export interface AssignmentRuleCopySummary {
  copiedCount: number
  skippedCount: number
  skippedReasons: string[]
}

export interface PublishProcessResult extends DeployProcessResult {
  rules?: AssignmentRuleCopySummary | null
  newUserTaskIds: string[]
}

export interface ProcessRelease {
  version: number
  action: 'PUBLISH' | 'ROLLBACK' | 'SUSPEND' | 'RESUME' | 'MIGRATED'
  sourceVersion?: number
  performedBy: string
  performedAt: string
}
```

- [ ] **Step 2: API**

Add to `definitionApi`:

```ts
  draft: (key: string) => apiClient.get<ProcessDraft>(`${ROOT}/drafts/${encodeURIComponent(key)}`),
  createDraft: (payload: { processDefinitionKey: string; name: string; bpmnXml: string }) =>
    apiClient.post<ProcessDraft>(`${ROOT}/drafts`, payload),
  saveDraft: (key: string, payload: { name: string; bpmnXml: string; revision: number }) =>
    apiClient.put<ProcessDraft>(`${ROOT}/drafts/${encodeURIComponent(key)}`, payload),
  loadVersionIntoDraft: (key: string, payload: { version: number; revision: number }) =>
    apiClient.post<ProcessDraft>(`${ROOT}/drafts/${encodeURIComponent(key)}/load-version`, payload),
  discardDraft: (key: string, revision: number) =>
    apiClient.post<ProcessDraft>(`${ROOT}/drafts/${encodeURIComponent(key)}/discard`, { revision }),
  publish: (key: string, revision: number) =>
    apiClient.post<PublishProcessResult>(`${ROOT}/${encodeURIComponent(key)}/publish`, { revision }),
  rollback: (key: string, version: number) =>
    apiClient.post<PublishProcessResult>(`${ROOT}/${encodeURIComponent(key)}/rollback`, { version }),
  suspend: (key: string) => apiClient.post<ProcessRelease>(`${ROOT}/${encodeURIComponent(key)}/suspend`),
  resume: (key: string) => apiClient.post<ProcessRelease>(`${ROOT}/${encodeURIComponent(key)}/resume`),
  releases: (key: string) => apiClient.get<ProcessRelease[]>(`${ROOT}/${encodeURIComponent(key)}/releases`),
```

Check that `apiClient` has `put`. If it does not, add it next to `post`, following the same pattern. Remove `activate` and `active` from `definitionApi` once Task 11 no longer uses them.

- [ ] **Step 3: Status label**

In `utils/status.ts`, add `SUSPENDED: { label: 'Đã ngừng', tone: 'warning' },`.

- [ ] **Step 4: Failing helper test**

```ts
import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/http'
import { designerStatus, isDraftConflict } from './draftState'

const draft = { processDefinitionKey: 'k', name: 'K', bpmnXml: '', revision: 1, suspended: false, hasUnpublishedChanges: true }

describe('designerStatus', () => {
  it('is draft-only before the first publish', () => {
    expect(designerStatus({ ...draft, latestVersion: undefined })).toEqual({
      status: 'DRAFT', label: 'Chỉ có bản nháp', canPublish: true, canSuspend: false, canResume: false,
    })
  })

  it('shows the running version and pending changes', () => {
    expect(designerStatus({ ...draft, latestVersion: 3 })).toMatchObject({
      status: 'PUBLISHED', label: 'Đang chạy v3 · có thay đổi chưa phát hành', canPublish: true, canSuspend: true,
    })
  })

  it('blocks publishing an unchanged running draft', () => {
    expect(designerStatus({ ...draft, latestVersion: 3, hasUnpublishedChanges: false }).canPublish).toBe(false)
  })

  it('allows publishing or resuming a suspended process', () => {
    expect(designerStatus({ ...draft, latestVersion: 2, suspended: true, hasUnpublishedChanges: false }))
      .toMatchObject({ status: 'SUSPENDED', canPublish: false, canResume: true, canSuspend: false })
  })
})

describe('isDraftConflict', () => {
  it('recognises the revision conflict code only', () => {
    expect(isDraftConflict(new ApiError('x', 'DRAFT_REVISION_CONFLICT', 409))).toBe(true)
    expect(isDraftConflict(new ApiError('x', 'CONFLICT', 409))).toBe(false)
    expect(isDraftConflict(new Error('x'))).toBe(false)
  })
})
```

Check the `ApiError` constructor parameter order in `src/api/http.ts` (message, code, status, traceId) and adjust if needed.

- [ ] **Step 5: Implement**

```ts
import { ApiError } from '@/api/http'
import type { ProcessDraft } from './types'

export interface DesignerStatus {
  status: 'DRAFT' | 'PUBLISHED' | 'SUSPENDED'
  label: string
  canPublish: boolean
  canSuspend: boolean
  canResume: boolean
}

export function designerStatus(draft: ProcessDraft): DesignerStatus {
  if (!draft.latestVersion) {
    return { status: 'DRAFT', label: 'Chỉ có bản nháp', canPublish: true, canSuspend: false, canResume: false }
  }
  const pending = draft.hasUnpublishedChanges ? ' · có thay đổi chưa phát hành' : ''
  if (draft.suspended) {
    return {
      status: 'SUSPENDED',
      label: `Đã ngừng v${draft.latestVersion}${pending}`,
      canPublish: draft.hasUnpublishedChanges,
      canSuspend: false,
      canResume: true,
    }
  }
  return {
    status: 'PUBLISHED',
    label: `Đang chạy v${draft.latestVersion}${pending}`,
    canPublish: draft.hasUnpublishedChanges,
    canSuspend: true,
    canResume: false,
  }
}

export function isDraftConflict(error: unknown) {
  return error instanceof ApiError && error.code === 'DRAFT_REVISION_CONFLICT'
}
```

- [ ] **Step 6: Run and commit**

Run: `pnpm vitest run src/features/process-definition/draftState.spec.ts`. Expected: PASS.

```bash
git add workflow-agent-web
git commit -m "feat: web API and status for process drafts"
```

---

### Task 11: Designer

**Files:**
- Modify: `workflow-agent-web/src/features/process-definition/views/ProcessDesignerView.vue`

The designer has two modes:
- **draft** (default): editable, backed by `draft.value`
- **viewing vN**: read-only, opened from the version select

- [ ] **Step 1: State**

Replace the `activeVersion` / `isSelectedActive` refs with:

```ts
const draft = ref<ProcessDraft>()
const viewingVersion = ref<number>()             // undefined = editing the draft
const status = computed(() => (draft.value ? designerStatus(draft.value) : undefined))
const readOnly = computed(() => viewingVersion.value !== undefined)
```

Keep `versions` (from `listVersions`). `selectedVersion` becomes `viewingVersion`.

- [ ] **Step 2: Loading**

Replace `loadVersions`/`openVersion` with:

```ts
async function loadProcess(key: string) {
  loading.value = true
  try {
    const [loadedDraft, loadedVersions] = await Promise.all([
      definitionApi.draft(key),
      definitionApi.listVersions(key).catch(() => []),
    ])
    draft.value = loadedDraft
    versions.value = loadedVersions.sort((a, b) => b.version - a.version)
    viewingVersion.value = undefined
    processForm.key = loadedDraft.processDefinitionKey
    processForm.name = loadedDraft.name
    await importXml(loadedDraft.bpmnXml, false)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function viewVersion(version: number | undefined) {
  if (version === undefined) return loadProcess(processForm.key)
  if (dirty.value && !(await confirmLeaveDraft())) return
  loading.value = true
  try {
    const detail = await definitionApi.detail(processForm.key, version)
    viewingVersion.value = detail.version
    await importXml(detail.bpmnXml, false)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}
```

`confirmLeaveDraft()` wraps the existing "Bạn chưa lưu…" `confirmAction`. In `onMounted`, call `loadProcess(key)` instead of `loadVersions(key)`. While `readOnly` is true, set `modeler.value.get('keyboard')`-free read-only behavior by hiding the palette, context pad and properties editing. The simplest form: wrap the canvas in a `.read-only` class with `pointer-events: none` on `.djs-palette, .djs-context-pad`, and disable the inspector forms with `:disabled="readOnly"`.

- [ ] **Step 3: Save draft**

Rename `saveVersion` to `saveDraft`. Keep everything up to `validateBpmnXml(xml)`, then:

```ts
    const name = processForm.name.trim()
    const key = processForm.key.trim()
    draft.value = draft.value
      ? await definitionApi.saveDraft(key, { name, bpmnXml: xml, revision: draft.value.revision })
      : await definitionApi.createDraft({ processDefinitionKey: key, name, bpmnXml: xml })
    dirty.value = false
    ElMessage.success('Đã lưu bản nháp')
```

In the `catch`, when `isDraftConflict(error)`, open a confirmation with two actions:
- «Tải lại»: `loadProcess(key)`, which discards local edits.
- «Ghi đè»: re-fetch the draft to get its current revision, then call `saveDraft` again with that revision.

Otherwise, show `getErrorMessage(error)`.

Toolbar button label: «Lưu nháp», disabled when `readOnly`.

- [ ] **Step 4: Publish and lifecycle**

```ts
async function publish() {
  if (!draft.value) return
  if (dirty.value) await saveDraft()
  if (dirty.value || !draft.value) return
  publishing.value = true
  try {
    const result = await definitionApi.publish(draft.value.processDefinitionKey, draft.value.revision)
    const copied = result.rules ? `, chép ${result.rules.copiedCount} luật phân công` : ''
    ElMessage.success(`Đã phát hành v${result.version}${copied}`)
    if (result.newUserTaskIds.length) {
      ElMessage.warning(`Bước mới cần kiểm tra phân công: ${result.newUserTaskIds.join(', ')}`)
    }
    await loadProcess(draft.value.processDefinitionKey)
  } catch (error) {
    if (isDraftConflict(error)) ElMessage.error('Bản nháp đã đổi. Hãy tải lại trước khi phát hành.')
    else ElMessage.error(getErrorMessage(error))
  } finally {
    publishing.value = false
  }
}

async function suspendProcess() {
  if (!(await confirmAction('Ngừng phát hành? Không ai bắt đầu mới được. Việc đang chạy vẫn tiếp tục.',
      'Ngừng phát hành', { confirmButtonText: 'Ngừng', cancelButtonText: 'Hủy', type: 'warning' }))) return
  await runAndReload(() => definitionApi.suspend(processForm.key), 'Đã ngừng phát hành')
}

async function resumeProcess() {
  await runAndReload(() => definitionApi.resume(processForm.key), 'Đã phát hành lại')
}

async function discardDraftChanges() {
  if (!draft.value || !(await confirmAction('Bỏ mọi thay đổi chưa phát hành?', 'Huỷ thay đổi',
      { confirmButtonText: 'Bỏ thay đổi', cancelButtonText: 'Hủy', type: 'warning' }))) return
  await runAndReload(() => definitionApi.discardDraft(processForm.key, draft.value!.revision), 'Đã khôi phục bản đang chạy')
}

async function openVersionInDraft() {
  if (!draft.value || viewingVersion.value === undefined) return
  await runAndReload(() => definitionApi.loadVersionIntoDraft(processForm.key,
    { version: viewingVersion.value!, revision: draft.value!.revision }), `Đã mở v${viewingVersion.value} vào bản nháp`)
}

async function rollbackToVersion() {
  const version = viewingVersion.value
  if (version === undefined || !(await confirmAction(
      `Phát hành lại nội dung của v${version} thành version mới?`, 'Quay về version này',
      { confirmButtonText: 'Quay về', cancelButtonText: 'Hủy', type: 'warning' }))) return
  await runAndReload(() => definitionApi.rollback(processForm.key, version), `Đã quay về nội dung v${version}`)
}

async function runAndReload(action: () => Promise<unknown>, message: string) {
  try {
    await action()
    ElMessage.success(message)
    await loadProcess(processForm.key)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}
```

Remove `publishVersion` and `inheritRules`. Rule copying now happens on publish. The rules screen still has its own "inherit" button for manual use.

- [ ] **Step 5: Version card template**

Replace the «Quản lý phiên bản» section body (the status row through the danger zone) with:

```vue
          <div class="version-status-row">
            <span>Trạng thái</span>
            <StatusBadge v-if="status" :status="status.status" :label="status.label" />
          </div>
          <el-select
            :model-value="viewingVersion"
            placeholder="Bản nháp"
            clearable
            :disabled="loading"
            @change="viewVersion"
          >
            <el-option label="Bản nháp (đang sửa)" :value="undefined" />
            <el-option
              v-for="item in versions"
              :key="item.version"
              :label="`${formatVersion(item.version)} · ${formatDateTime(item.deployedAt)}`"
              :value="item.version"
            />
          </el-select>
          <template v-if="!readOnly">
            <el-button type="primary" :disabled="!status?.canPublish && !dirty" :loading="publishing" @click="publish">
              Phát hành
            </el-button>
            <el-button v-if="status?.canSuspend" @click="suspendProcess">Ngừng phát hành</el-button>
            <el-button v-if="status?.canResume" @click="resumeProcess">Phát hành lại</el-button>
            <el-button v-if="draft?.latestVersion && draft.hasUnpublishedChanges" @click="discardDraftChanges">
              Huỷ thay đổi nháp
            </el-button>
          </template>
          <template v-else>
            <el-alert type="info" :closable="false" :title="`Đang xem ${formatVersion(viewingVersion!)} (chỉ xem)`" />
            <el-button @click="openVersionInDraft">Mở vào bản nháp</el-button>
            <el-button v-if="viewingVersion !== draft?.latestVersion" @click="rollbackToVersion">
              Quay về version này
            </el-button>
          </template>
          <div class="danger-zone">
            <span>Xóa</span>
            <el-button
              type="danger" plain
              :disabled="!readOnly || viewingVersion === draft?.latestVersion"
              @click="deleteVersion"
            >
              <Trash2 :size="15" />Xóa version đang xem
            </el-button>
            <el-button type="danger" plain :disabled="!draft" @click="deleteDefinition">
              <Trash2 :size="15" />Xóa quy trình
            </el-button>
          </div>
```

Update the section description to: «Lưu nháp không ảnh hưởng tới việc đang chạy. Phát hành để mọi người dùng bản mới.» `deleteVersion` uses `viewingVersion` and ends with `loadProcess(key)`.

- [ ] **Step 6: New process**

In `createNewProcess`, nothing is saved until the first «Lưu nháp». Keep setting `draft.value = undefined`, so `saveDraft` takes the `createDraft` branch. `definitionApi.exists` already covers drafts (Task 7).

- [ ] **Step 7: Run and commit**

Run: `pnpm check`. Expected: PASS (type-check, lint, unit tests).

```bash
git add workflow-agent-web
git commit -m "feat: designer saves drafts and publishes explicitly"
```

---

### Task 12: List view

**Files:**
- Modify: `workflow-agent-web/src/features/process-definition/views/ProcessDefinitionListView.vue`

- [ ] **Step 1: Filter options**

Replace the status options with: «Tất cả» `all`, «Đang chạy» `published`, «Đã ngừng» `suspended`, «Chỉ bản nháp» `draft_only`.

- [ ] **Step 2: Status cell**

```vue
<StatusBadge
  :status="row.publishStatus === 'published' ? 'PUBLISHED' : row.publishStatus === 'suspended' ? 'SUSPENDED' : 'DRAFT'"
  :label="row.publishStatus === 'published' ? formatVersion(row.activeVersion)
    : row.publishStatus === 'suspended' ? 'Đã ngừng' : 'Bản nháp'"
/>
<el-tag v-if="row.hasUnpublishedChanges" size="small" type="warning">Có thay đổi</el-tag>
```

Show «—» for the latest version column when `row.latestVersion === 0`. Keep the "start" action condition `row.activeVersion`. It is now `null` for suspended and draft-only rows.

- [ ] **Step 3: Counters**

`publishedCount` counts `publishStatus === 'published'`. `draftCount` counts `draft_only`.

- [ ] **Step 4: `StartProcessDialog`**

No logic change is needed. The list from `/definitions` (latest versions) is filtered by `active`, which is now "latest and not suspended". Add a comment above `activeDefinitions` that says so, so nobody adds an extra version filter back.

- [ ] **Step 5: Run and commit**

Run: `pnpm check`. Expected: PASS.

```bash
git add workflow-agent-web
git commit -m "feat: process list shows draft, running and suspended states"
```

---

### Task 13: Full verification and rollout

- [ ] **Step 1: Backend.** `mvn verify` from `workflow-agent-service`. Expected: BUILD SUCCESS, including SpotBugs, JaCoCo thresholds and ArchUnit. Run it on a machine with Docker, so the Testcontainers tests are not skipped.
- [ ] **Step 2: Web.** `pnpm check` from `workflow-agent-web`. Expected: PASS.
- [ ] **Step 3: Dev stack walkthrough.**
  1. Before deploying the new build, record each key's active version and latest version.
  2. Deploy the new build.
  3. `POST /workflow/management/process/migrations/draft-publish` (dry run) and compare the report with step 1.
  4. Run it with `dryRun=false`.
  5. For one key per scenario:
     - open it in the designer and confirm the draft content
     - start an instance and confirm the version
     - save a draft and confirm that start still uses the published version
     - publish, suspend, resume and roll back
- [ ] **Step 4: Release note** for `SERVICE` clients:
  - starting with an old `processDefinitionId` is rejected
  - `/definition/activate` and `/definition/active` are deprecated and will be removed after one release
  - `/deploy` now publishes immediately, which is unchanged in effect for CI
- [ ] **Step 5: Follow-up ticket:** a migration that drops `workflow_active_version` and removes the two deprecated endpoints, scheduled for the next release.
- [ ] **Step 6: Commit fixes from this task**

```bash
git add -A
git commit -m "chore: verify draft and publish end to end"
```
