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

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 5, 9, 0, 0, 0, ZoneOffset.UTC);

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
        assertThat(drafts.findForUpdate("t1", "order")).isPresent();
        assertThat(drafts.find("t2", "order")).isEmpty();
        assertThat(drafts.listHeaders("t1")).extracting("processDefinitionKey").containsExactly("order");

        drafts.delete("t1", "order");
        assertThat(drafts.find("t1", "order")).isEmpty();
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
