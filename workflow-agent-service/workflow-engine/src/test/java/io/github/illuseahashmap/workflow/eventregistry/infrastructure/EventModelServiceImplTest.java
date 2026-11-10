package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventFieldView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventModelView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveEventModelRequest;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryModelType;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventModelServiceImplTest {

    static final TenantContext.TenantInfo TENANT = new TenantContext.TenantInfo("t1", "t1", "Tenant 1");
    static final CurrentPrincipal DESIGNER = new CurrentPrincipal("USER", "u-1", "designer", "Designer", "t1",
            Set.of("WF_ADMIN"), Set.of());
    static final EventFieldView ORDER_ID = new EventFieldView("orderId", "string", true, false, false);
    static final EventFieldView AMOUNT = new EventFieldView("amount", "double", false, false, false);

    private static EventRegistryTestEngine test;
    private InMemoryEventRegistryRepositories repositories;
    private EventRegistryProperties properties;
    private WorkflowOperationAuditService audit;
    private EventModelServiceImpl service;

    @BeforeAll
    static void start() {
        test = new EventRegistryTestEngine("event-model-service");
    }

    @AfterAll
    static void stop() {
        test.close();
    }

    @BeforeEach
    void setUp() {
        repositories = new InMemoryEventRegistryRepositories();
        properties = new EventRegistryProperties();
        properties.setEnabled(true);
        audit = mock(WorkflowOperationAuditService.class);
        service = service(repositories, properties, audit);
    }

    static EventModelServiceImpl service(InMemoryEventRegistryRepositories repositories,
                                         EventRegistryProperties properties, WorkflowOperationAuditService audit) {
        return new EventModelServiceImpl(repositories.drafts, repositories.releases, repositories.references,
                test.gateway, audit, () -> TENANT, () -> DESIGNER, properties);
    }

    static long versions(String key) {
        return test.events.getEventRepositoryService().createEventDefinitionQuery()
                .eventDefinitionKey(key).tenantId("t1").count();
    }

    private EventModelView save(String key, long revision, EventFieldView... fields) {
        return service.saveDraft(key, new SaveEventModelRequest(revision, "Đã thanh toán", null, List.of(fields)));
    }

    @Test
    void publishesOnceAndSkipsUnchangedContent() {
        EventModelView draft = save("paidOnce", 0, ORDER_ID, AMOUNT);
        assertThat(draft.changed()).isTrue();

        EventModelView published = service.publish("paidOnce", draft.revision());
        service.publish("paidOnce", draft.revision());

        assertThat(published.publishedVersion()).isEqualTo(1);
        assertThat(published.changed()).isFalse();
        assertThat(published.publishedFields()).containsExactly(ORDER_ID, AMOUNT);
        assertThat(versions("paidOnce")).isEqualTo(1);
        verify(audit).record(eq("EVENT_MODEL_PUBLISHED"), any(), any(), any(), eq("paidOnce"), any(),
                eq("PUBLISHED"), eq("version=1"));
    }

    @Test
    void addingAPlainFieldPublishesANewVersionRemovingOneIsRefused() {
        long revision = save("paidGrow", 0, ORDER_ID, AMOUNT).revision();
        service.publish("paidGrow", revision);

        EventModelView grown = save("paidGrow", revision, ORDER_ID, AMOUNT,
                new EventFieldView("note", "string", false, false, false));
        assertThat(grown.changed()).isTrue();
        assertThat(service.publish("paidGrow", grown.revision()).publishedVersion()).isEqualTo(2);

        EventModelView shrunk = save("paidGrow", grown.revision(), ORDER_ID);
        assertThatThrownBy(() -> service.publish("paidGrow", shrunk.revision()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getMessage()).startsWith("Thay đổi không tương thích:")
                            .contains("Xóa trường amount").contains("Xóa trường note");
                });
        assertThat(versions("paidGrow")).isEqualTo(2);
    }

    @Test
    void eventWithAWaitingInstanceCannotBeDeleted() {
        service.publish("paidWait", save("paidWait", 0, ORDER_ID, AMOUNT).revision());
        test.deployWaitingProcess("t1", "paidWaitProcess", "paidWait");
        test.engine.getRuntimeService().startProcessInstanceByKeyAndTenantId("paidWaitProcess",
                Map.of("orderId", "A-1"), "t1");

        assertThatThrownBy(() -> service.delete("paidWait")).hasMessageContaining("Còn 1 công việc");
        assertThat(versions("paidWait")).isEqualTo(1);
    }

    @Test
    void deleteRemovesDraftAndDeployment() {
        service.publish("paidGone", save("paidGone", 0, ORDER_ID, AMOUNT).revision());

        service.delete("paidGone");

        assertThat(versions("paidGone")).isZero();
        assertThat(service.list()).noneMatch(model -> model.key().equals("paidGone"));
        assertThat(repositories.releases.latest("t1",
                RegistryModelType.EVENT, "paidGone"))
                .hasValueSatisfying(release -> assertThat(release.action()).isEqualTo("DELETE"));
    }

    @Test
    void writesNeedTheFlagReadsDoNot() {
        save("paidFlag", 0, ORDER_ID);
        properties.setEnabled(false);

        assertThatThrownBy(() -> save("paidFlag2", 0, ORDER_ID))
                .hasMessageContaining("Tính năng sự kiện chưa được bật");
        assertThat(service.list()).extracting(EventModelView::key).containsExactly("paidFlag");
    }

    @Test
    void staleRevisionIsAConflict() {
        long revision = save("paidStale", 0, ORDER_ID).revision();
        save("paidStale", revision, ORDER_ID, AMOUNT);

        assertThatThrownBy(() -> save("paidStale", revision, ORDER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DRAFT_REVISION_CONFLICT));
        assertThatThrownBy(() -> service.publish("paidStale", revision))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DRAFT_REVISION_CONFLICT));
    }

    @Test
    void invalidDraftAndDuplicateKeyAreRefused() {
        assertThatThrownBy(() -> save("paidInvalid", 0, AMOUNT))
                .hasMessageContaining("tương quan");
        save("paidTwice", 0, ORDER_ID);
        assertThatThrownBy(() -> save("paidTwice", 0, ORDER_ID)).hasMessageContaining("Sự kiện đã tồn tại");
    }
}
