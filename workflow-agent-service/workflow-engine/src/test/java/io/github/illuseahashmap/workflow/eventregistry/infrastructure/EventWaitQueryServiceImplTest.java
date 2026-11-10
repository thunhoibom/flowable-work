package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventUsageView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventUsagesView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventWaitView;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventReference;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EventWaitQueryServiceImplTest {

    private static final TenantContext.TenantInfo T1 = new TenantContext.TenantInfo("t1", "t1", "Tenant 1");
    private static final TenantContext.TenantInfo T2 = new TenantContext.TenantInfo("t2", "t2", "Tenant 2");
    private static final AtomicReference<TenantContext.TenantInfo> TENANT = new AtomicReference<>(T1);

    private static EventRegistryTestEngine test;
    private static InMemoryEventRegistryRepositories.References references;
    private static EventWaitQueryServiceImpl service;

    @BeforeAll
    static void start() {
        test = new EventRegistryTestEngine("event-wait-query");
        references = new InMemoryEventRegistryRepositories().references;
        service = new EventWaitQueryServiceImpl(test.engine.getRuntimeService(), test.engine.getRepositoryService(),
                references, test.gateway, TENANT::get);
        test.deployWaitingProcess("t1", "queryOne", "queryEvent");
        test.deployWaitingProcess("t2", "queryTwo", "queryEvent");
        for (int index = 0; index < 3; index++) {
            startIn("t1", "queryOne", "A-" + index);
        }
        startIn("t2", "queryTwo", "B-1");
    }

    @AfterAll
    static void stop() {
        test.close();
    }

    private static ProcessInstance startIn(String tenantId, String key, String orderId) {
        return test.engine.getRuntimeService().startProcessInstanceByKeyAndTenantId(key, "business-" + orderId,
                Map.of("orderId", orderId), tenantId);
    }

    @Test
    void listsOnlyTheCallersWaitsAndPages() {
        TENANT.set(T1);

        PageResult<EventWaitView> first = service.waits("queryEvent", 1, 2);
        PageResult<EventWaitView> second = service.waits("queryEvent", 2, 2);

        assertThat(first.total()).isEqualTo(3);
        assertThat(first.records()).hasSize(2);
        assertThat(second.records()).hasSize(1);
        assertThat(first.records()).allSatisfy(wait -> {
            assertThat(wait.processDefinitionKey()).isEqualTo("queryOne");
            assertThat(wait.activityId()).isEqualTo("wait");
            assertThat(wait.businessKey()).startsWith("business-A-");
            assertThat(wait.executionId()).isNotBlank();
            assertThat(wait.since()).isNotNull();
        });

        TENANT.set(T2);
        assertThat(service.waits("queryEvent", 1, 20).records()).singleElement()
                .satisfies(wait -> assertThat(wait.businessKey()).isEqualTo("business-B-1"));
    }

    @Test
    void capsThePageSizeAndRejectsBadKeys() {
        TENANT.set(T1);

        assertThat(service.waits("queryEvent", 1, 1000).pageSize()).isEqualTo(EventWaitQueryServiceImpl.MAX_PAGE_SIZE);
        assertThatThrownBy(() -> service.waits(" ", 1, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.waits("${x}", 1, 20)).isInstanceOf(BusinessException.class);
    }

    @Test
    void groupsUsagesByProcessVersion() {
        TENANT.set(T1);
        references.replaceForDefinition("t1", "orders:3:abc", List.of(
                new EventReference("orders:3:abc", "pay", "queryEvent"),
                new EventReference("orders:3:abc", "refund", "queryEvent")));
        references.replaceForDefinition("t1", "billing:1:def", List.of(
                new EventReference("billing:1:def", "wait", "queryEvent")));

        EventUsagesView usages = service.usages("queryEvent");

        assertThat(usages.usages()).extracting(EventUsageView::processDefinitionKey, EventUsageView::activityIds)
                .containsExactly(
                        tuple("billing", List.of("wait")),
                        tuple("orders", List.of("pay", "refund")));
        assertThat(usages.waiting()).isEqualTo(3);
    }
}
