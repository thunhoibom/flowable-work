package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.eventregistry.application.EventWaitQueryService;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventUsageView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventUsagesView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventWaitView;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventReferenceRepository;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryGateway;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.eventsubscription.api.EventSubscription;
import org.springframework.stereotype.Service;

@Service
public class EventWaitQueryServiceImpl implements EventWaitQueryService {

    static final int MAX_PAGE_SIZE = 100;
    private static final Pattern EVENT_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    private final RuntimeService runtimeService;
    private final RepositoryService repositoryService;
    private final EventReferenceRepository references;
    private final EventRegistryGateway gateway;
    private final TenantProvider tenants;

    public EventWaitQueryServiceImpl(RuntimeService runtimeService, RepositoryService repositoryService,
                                     EventReferenceRepository references, EventRegistryGateway gateway,
                                     TenantProvider tenants) {
        this.runtimeService = runtimeService;
        this.repositoryService = repositoryService;
        this.references = references;
        this.gateway = gateway;
        this.tenants = tenants;
    }

    @Override
    public PageResult<EventWaitView> waits(String eventKey, int page, int size) {
        String key = requireKey(eventKey);
        String tenantId = tenants.current().tenantId();
        int pageNum = Math.max(page, 1);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long total = runtimeService.createEventSubscriptionQuery().tenantId(tenantId).eventType(key).count();
        List<EventSubscription> subscriptions = runtimeService.createEventSubscriptionQuery()
                .tenantId(tenantId).eventType(key).orderByCreateDate().desc()
                .listPage((pageNum - 1) * pageSize, pageSize);
        Set<String> instanceIds = subscriptions.stream().map(EventSubscription::getProcessInstanceId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, ProcessInstance> instances = instanceIds.isEmpty() ? Map.of()
                : runtimeService.createProcessInstanceQuery().processInstanceTenantId(tenantId)
                        .processInstanceIds(instanceIds).list().stream()
                        .collect(Collectors.toMap(ProcessInstance::getId, Function.identity()));
        Map<String, BpmnModel> models = new HashMap<>();
        List<EventWaitView> records = new ArrayList<>();
        for (EventSubscription subscription : subscriptions) {
            ProcessInstance instance = instances.get(subscription.getProcessInstanceId());
            String activityName = null;
            if (subscription.getProcessDefinitionId() != null) {
                FlowElement element = models.computeIfAbsent(subscription.getProcessDefinitionId(),
                        repositoryService::getBpmnModel).getFlowElement(subscription.getActivityId());
                activityName = element == null ? null : element.getName();
            }
            records.add(new EventWaitView(subscription.getProcessInstanceId(),
                    instance == null ? null : instance.getProcessDefinitionKey(),
                    instance == null ? null : instance.getProcessDefinitionName(),
                    instance == null ? null : instance.getBusinessKey(),
                    subscription.getActivityId(), activityName, subscription.getExecutionId(),
                    subscription.getCreated() == null ? null
                            : subscription.getCreated().toInstant().atOffset(ZoneOffset.UTC)));
        }
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public EventUsagesView usages(String eventKey) {
        String key = requireKey(eventKey);
        String tenantId = tenants.current().tenantId();
        Map<String, EventUsageView> grouped = new LinkedHashMap<>();
        for (EventReferenceRepository.EventUsage usage : references.usages(tenantId, key)) {
            grouped.merge(usage.processDefinitionKey() + ":" + usage.version(),
                    new EventUsageView(usage.processDefinitionKey(), usage.version(), List.of(usage.activityId())),
                    (left, right) -> {
                        List<String> activityIds = new ArrayList<>(left.activityIds());
                        activityIds.addAll(right.activityIds());
                        return new EventUsageView(left.processDefinitionKey(), left.version(), activityIds);
                    });
        }
        return new EventUsagesView(List.copyOf(grouped.values()), gateway.waitingSubscriptions(tenantId, key));
    }

    private static String requireKey(String eventKey) {
        String key = eventKey == null ? "" : eventKey.trim();
        if (!EVENT_KEY.matcher(key).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Mã sự kiện không hợp lệ");
        }
        return key;
    }
}
