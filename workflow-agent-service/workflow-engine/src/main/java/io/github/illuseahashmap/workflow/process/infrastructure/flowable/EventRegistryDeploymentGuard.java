package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryGateway;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.process.application.ProcessVariablePolicy;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Event registry elements on a published diagram (spec 2026-11-09-event-registry-d1-design).
 * Event waits on receive tasks, intermediate catch events and boundary events are checked against
 * the tenant's published events. Event start, send-event and trigger events belong to later phases.
 * A receive task after an event-based gateway is rejected for good: Flowable does not support it.
 */
@Component
public class EventRegistryDeploymentGuard {

    private static final String BPMN_NS = EventWaitBindingParser.BPMN_NS;
    private static final Set<String> FLOWABLE_NS = EventWaitBindingParser.FLOWABLE_NS;
    private static final Set<String> WAIT_ELEMENTS = Set.of("receiveTask", "intermediateCatchEvent", "boundaryEvent");
    private static final Set<String> TRIGGER_ELEMENTS = Set.of("triggerEventType", "triggerEventCorrelationParameter");
    private static final Pattern EVENT_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
    private static final Pattern VARIABLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    private final EventRegistryProperties properties;
    private final EventRegistryGateway gateway;

    public EventRegistryDeploymentGuard(EventRegistryProperties properties, EventRegistryGateway gateway) {
        this.properties = properties;
        this.gateway = gateway;
    }

    /** Throws on the first unsupported shape, or with every wait problem; returns the waits that passed. */
    public List<EventWaitBinding> check(TenantContext.TenantInfo tenant, String bpmnXml) {
        Document document = EventWaitBindingParser.document(bpmnXml);
        rejectLaterPhases(document);
        rejectReceiveTasksAfterEventGateways(document);
        List<EventWaitBinding> bindings = EventWaitBindingParser.parse(document);
        for (EventWaitBinding binding : bindings) {
            if (binding.agentTask()) {
                throw invalid("Bước " + binding.activityId() + " không thể vừa là tác vụ Agent vừa chờ sự kiện");
            }
        }
        if (bindings.isEmpty()) {
            return List.of();
        }
        if (!properties.isEnabled()) {
            throw invalid("Bước " + bindings.getFirst().activityId()
                    + " chờ sự kiện nhưng tính năng sự kiện chưa được bật");
        }
        Map<String, Optional<List<EventField>>> published = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (EventWaitBinding binding : bindings) {
            problems.addAll(problems(binding, key -> published.computeIfAbsent(key,
                    ignored -> gateway.publishedEventFields(tenant.tenantId(), key))));
        }
        if (!problems.isEmpty()) {
            throw invalid(String.join("; ", problems));
        }
        return bindings;
    }

    private static List<String> problems(EventWaitBinding binding,
                                         Function<String, Optional<List<EventField>>> fields) {
        String step = "Bước " + binding.activityId();
        List<String> problems = new ArrayList<>();
        if (binding.hasEventDefinition()) {
            problems.add(step + " không được vừa có định nghĩa sự kiện vừa chờ sự kiện Event Registry");
            return problems;
        }
        String key = binding.eventKey();
        if (key.isBlank() || key.contains("${") || key.contains("#{") || !EVENT_KEY.matcher(key).matches()) {
            problems.add(step + ": mã sự kiện phải là mã cố định hợp lệ");
            return problems;
        }
        Optional<List<EventField>> event = fields.apply(key);
        if (event.isEmpty()) {
            problems.add(step + ": sự kiện " + key + " chưa được phát hành");
            return problems;
        }
        if (binding.correlations().isEmpty()) {
            problems.add(step + " phải có ít nhất một trường tương quan");
        }
        Set<String> correlationFields = new HashSet<>();
        Set<String> allFields = new HashSet<>();
        for (EventField field : event.get()) {
            allFields.add(field.name());
            if (field.correlation()) {
                correlationFields.add(field.name());
            }
        }
        Set<String> seen = new HashSet<>();
        for (EventWaitBinding.Correlation correlation : binding.correlations()) {
            String name = correlation.name();
            if (!correlationFields.contains(name)) {
                problems.add(step + ": sự kiện " + key + " không có trường tương quan " + name);
            } else if (!seen.add(name)) {
                problems.add(step + ": trường tương quan " + name + " bị lặp");
            } else if (correlation.value() == null || correlation.value().isBlank()) {
                problems.add(step + ": trường tương quan " + name + " chưa có giá trị");
            }
        }
        Set<String> targets = new HashSet<>();
        for (EventWaitBinding.Output output : binding.outputs()) {
            String target = output.target() == null ? "" : output.target().trim();
            if (!allFields.contains(output.source())) {
                problems.add(step + ": sự kiện " + key + " không có trường " + output.source());
            }
            if (!VARIABLE.matcher(target).matches()) {
                problems.add(step + ": tên biến " + target + " không hợp lệ");
            } else if (ProcessVariablePolicy.isInternalVariable(target)) {
                problems.add(step + ": biến " + target + " do hệ thống quản lý");
            } else if (!targets.add(target)) {
                problems.add(step + ": biến " + target + " được gán hai lần");
            }
        }
        return problems;
    }

    private static void rejectLaterPhases(Document document) {
        NodeList all = document.getElementsByTagNameNS("*", "*");
        for (int index = 0; index < all.getLength(); index++) {
            Element element = (Element) all.item(index);
            if (FLOWABLE_NS.contains(element.getNamespaceURI())) {
                Element owner = owner(element);
                String id = owner == null ? "?" : owner.getAttribute("id");
                if (TRIGGER_ELEMENTS.contains(element.getLocalName())) {
                    throw laterPhase(id, "chờ phản hồi sau khi gửi sự kiện");
                }
                if ("eventType".equals(element.getLocalName())
                        && (owner == null || !WAIT_ELEMENTS.contains(owner.getLocalName()))) {
                    throw laterPhase(id, owner != null && "startEvent".equals(owner.getLocalName())
                            ? "bắt đầu quy trình bằng sự kiện"
                            : "sự kiện trên " + (owner == null ? "?" : owner.getLocalName()));
                }
            }
            if (BPMN_NS.equals(element.getNamespaceURI()) && "serviceTask".equals(element.getLocalName())
                    && "send-event".equals(flowableAttribute(element, "type"))) {
                throw laterPhase(element.getAttribute("id"), "gửi sự kiện");
            }
        }
    }

    private static void rejectReceiveTasksAfterEventGateways(Document document) {
        Set<String> gateways = ids(document, "eventBasedGateway");
        if (gateways.isEmpty()) {
            return;
        }
        Set<String> receiveTasks = ids(document, "receiveTask");
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

    private static Set<String> ids(Document document, String localName) {
        Set<String> ids = new HashSet<>();
        NodeList nodes = document.getElementsByTagNameNS(BPMN_NS, localName);
        for (int index = 0; index < nodes.getLength(); index++) {
            ids.add(((Element) nodes.item(index)).getAttribute("id"));
        }
        return ids;
    }

    /** The BPMN element that owns an extension element: the parent of its extensionElements. */
    private static Element owner(Element extension) {
        if (extension.getParentNode() instanceof Element parent
                && "extensionElements".equals(parent.getLocalName())
                && parent.getParentNode() instanceof Element owner) {
            return owner;
        }
        return null;
    }

    private static String flowableAttribute(Element element, String name) {
        for (String namespace : FLOWABLE_NS) {
            String value = element.getAttributeNS(namespace, name);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static BusinessException laterPhase(String id, String feature) {
        return invalid("Bước " + id + " dùng tính năng sự kiện của đợt sau (" + feature + "), chưa được hỗ trợ");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
