package io.github.illuseahashmap.workflow.process.infrastructure.flowable.subprocess;

import io.github.illuseahashmap.workflow.config.SubProcessProperties;
import io.github.illuseahashmap.workflow.process.application.ProcessVariablePolicy;
import io.github.illuseahashmap.workflow.process.application.dto.ParticipantRequirementView;
import io.github.illuseahashmap.workflow.process.application.port.CallReferenceRepository;
import io.github.illuseahashmap.workflow.process.domain.CallReference;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.FlowableParticipantAssignmentCoordinator;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.ProcessDefinitionLookup;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Publish-time rules for call activities. Stock Flowable resolves the callee by key to the latest
 * version in the tenant, which after draft/publish is always the published one. This guard makes
 * sure that what is called exists, cannot loop, and cannot step around tenant or variable rules.
 */
@Component
public class CallActivityDeploymentGuard {

    /** A call activity of the document being published. */
    public record Call(String activityId, String calledKey) {
    }

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final List<String> FLOWABLE_NAMESPACES = List.of(
            "http://flowable.org/bpmn", "http://activiti.org/bpmn");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
    private static final Pattern VARIABLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    private final SubProcessProperties properties;
    private final RepositoryService repositoryService;
    private final ProcessDefinitionLookup lookup;
    private final CallReferenceRepository references;
    private final FlowableParticipantAssignmentCoordinator participantCoordinator;

    public CallActivityDeploymentGuard(SubProcessProperties properties, RepositoryService repositoryService,
                                       ProcessDefinitionLookup lookup, CallReferenceRepository references,
                                       FlowableParticipantAssignmentCoordinator participantCoordinator) {
        this.properties = properties;
        this.repositoryService = repositoryService;
        this.lookup = lookup;
        this.references = references;
        this.participantCoordinator = participantCoordinator;
    }

    /** @return the call activities of the document, to be stored once the version is deployed */
    public List<Call> check(TenantContext.TenantInfo tenant, String key, String bpmnXml) {
        NodeList elements = parse(bpmnXml).getElementsByTagNameNS(BPMN_NS, "callActivity");
        if (elements.getLength() == 0) {
            return List.of();
        }
        if (!properties.isCallActivityEnabled()) {
            throw invalid("Call activities are disabled (workflow.subprocess.call-activity-enabled)");
        }
        List<Call> calls = new ArrayList<>();
        for (int index = 0; index < elements.getLength(); index++) {
            Element callActivity = (Element) elements.item(index);
            String called = checkCallActivity(tenant, callActivity);
            calls.add(new Call(callActivity.getAttribute("id"), called));
        }
        Set<String> calledKeys = calls.stream().map(Call::calledKey).collect(Collectors.toCollection(LinkedHashSet::new));
        checkGraph(tenant.tenantId(), key, calledKeys);
        calledKeys.forEach(called -> checkCalleeParticipants(tenant.tenantId(), called));
        return List.copyOf(calls);
    }

    private String checkCallActivity(TenantContext.TenantInfo tenant, Element callActivity) {
        String id = callActivity.getAttribute("id");
        String called = callActivity.getAttribute("calledElement").trim();
        if (!KEY.matcher(called).matches()) {
            throw invalid("Call activity " + id + " needs a calledElement that is a process key");
        }
        String type = flowableAttribute(callActivity, "calledElementType");
        if (type != null && !"key".equals(type)) {
            throw invalid("Call activity " + id + ": calledElementType must be key");
        }
        for (String option : List.of("inheritVariables", "fallbackToDefaultTenant", "sameDeployment")) {
            if ("true".equalsIgnoreCase(flowableAttribute(callActivity, option))) {
                throw invalid("Call activity " + id + ": " + option + " is not supported");
            }
        }
        if (flowableAttribute(callActivity, "calledElementBinding") != null) {
            throw invalid("Call activity " + id + ": calledElementBinding is not supported");
        }
        ProcessDefinition callee = lookup.latest(tenant.tenantId(), called).orElse(null);
        if (callee == null || callee.isSuspended()) {
            throw invalid("Call activity " + id + " calls " + called + ", which is not published");
        }
        checkParameters(id, callActivity);
        Element loop = child(callActivity, BPMN_NS, "multiInstanceLoopCharacteristics");
        if (loop != null && !"true".equals(loop.getAttribute("isSequential"))
                && !"true".equals(flowableAttribute(callActivity, "completeAsync"))) {
            throw invalid("Call activity " + id + " runs in parallel and needs completeAsync=\"true\"");
        }
        return called;
    }

    private void checkParameters(String id, Element callActivity) {
        List<Element> in = flowableChildren(callActivity, "in");
        List<Element> out = flowableChildren(callActivity, "out");
        if (in.size() + out.size() > properties.getMaxIoParameters()) {
            throw invalid("Call activity " + id + " may have at most " + properties.getMaxIoParameters()
                    + " in and out parameters");
        }
        checkDirection(id, "in", in);
        checkDirection(id, "out", out);
    }

    private static void checkDirection(String id, String direction, List<Element> parameters) {
        Set<String> targets = new HashSet<>();
        for (Element parameter : parameters) {
            String target = parameter.getAttribute("target").trim();
            String targetExpression = parameter.getAttribute("targetExpression").trim();
            if (target.isEmpty() && (!"out".equals(direction) || targetExpression.isEmpty())) {
                throw invalid("Call activity " + id + ": every " + direction + " parameter needs a target");
            }
            boolean source = !parameter.getAttribute("source").isBlank();
            boolean sourceExpression = !parameter.getAttribute("sourceExpression").isBlank();
            if (source == sourceExpression) {
                throw invalid("Call activity " + id + ": " + direction
                        + " parameter needs exactly one of source and sourceExpression");
            }
            if (source && !VARIABLE.matcher(parameter.getAttribute("source").trim()).matches()) {
                throw invalid("Call activity " + id + ": invalid " + direction + " source variable");
            }
            if (target.isEmpty()) {
                continue;
            }
            if (!VARIABLE.matcher(target).matches()) {
                throw invalid("Call activity " + id + ": invalid " + direction + " target " + target);
            }
            if (ProcessVariablePolicy.isInternalVariable(target)) {
                throw invalid("Call activity " + id + ": " + target + " is reserved by the platform");
            }
            if (!targets.add(target)) {
                throw invalid("Call activity " + id + ": " + direction + " target " + target + " is used twice");
            }
        }
    }

    /** No cycle through this key, and no chain through it longer than max-call-depth. */
    private void checkGraph(String tenantId, String key, Set<String> calledKeys) {
        Map<String, Set<String>> edges = publishedEdges(tenantId, key);
        edges.put(key, calledKeys);
        List<String> cycle = cycleThrough(key, edges);
        if (!cycle.isEmpty()) {
            throw invalid("Call activities form a cycle: " + String.join(" → ", cycle));
        }
        Map<String, Set<String>> reverse = new HashMap<>();
        edges.forEach((from, targets) -> targets.forEach(to -> reverse.computeIfAbsent(to, ignored -> new HashSet<>())
                .add(from)));
        int depth = longest(key, edges, new HashMap<>()) + longest(key, reverse, new HashMap<>()) - 1;
        if (depth > properties.getMaxCallDepth()) {
            throw invalid("A chain of " + depth + " processes through " + key
                    + " exceeds the maximum call depth " + properties.getMaxCallDepth());
        }
    }

    /** Edges of every other key's published version. Older or suspended versions do not count. */
    private Map<String, Set<String>> publishedEdges(String tenantId, String key) {
        Map<String, ProcessDefinition> published = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId).latestVersion().list().stream()
                .filter(definition -> !definition.isSuspended())
                .collect(Collectors.toMap(ProcessDefinition::getKey, Function.identity()));
        Map<String, Set<String>> edges = new HashMap<>();
        for (CallReference reference : references.findAll(tenantId)) {
            ProcessDefinition parent = published.get(reference.parentDefinitionKey());
            if (!reference.parentDefinitionKey().equals(key) && parent != null
                    && parent.getId().equals(reference.parentDefinitionId())) {
                edges.computeIfAbsent(reference.parentDefinitionKey(), ignored -> new LinkedHashSet<>())
                        .add(reference.calledKey());
            }
        }
        return edges;
    }

    private static List<String> cycleThrough(String key, Map<String, Set<String>> edges) {
        Deque<String> path = new ArrayDeque<>();
        path.addLast(key);
        return walk(key, key, edges, path, new HashSet<>()) ? List.copyOf(path) : List.of();
    }

    private static boolean walk(String start, String node, Map<String, Set<String>> edges,
                                Deque<String> path, Set<String> visited) {
        for (String next : edges.getOrDefault(node, Set.of())) {
            path.addLast(next);
            if (next.equals(start)) {
                return true;
            }
            if (visited.add(next) && walk(start, next, edges, path, visited)) {
                return true;
            }
            path.removeLast();
        }
        return false;
    }

    private static int longest(String node, Map<String, Set<String>> edges, Map<String, Integer> memo) {
        Integer known = memo.get(node);
        if (known != null) {
            return known;
        }
        memo.put(node, 1);
        int best = 1;
        for (String next : edges.getOrDefault(node, Set.of())) {
            best = Math.max(best, 1 + longest(next, edges, memo));
        }
        memo.put(node, best);
        return best;
    }

    /**
     * The callee has no participant picker screen yet, so its first user tasks must be assignable
     * on their own. When they cannot be evaluated without variables, the check is skipped.
     */
    private void checkCalleeParticipants(String tenantId, String called) {
        ProcessDefinition callee = lookup.latest(tenantId, called).orElseThrow();
        List<ParticipantRequirementView> requirements;
        try {
            requirements = participantCoordinator.requirementsForStart(tenantId, callee.getId(), Map.of());
        } catch (BusinessException exception) {
            return;
        }
        requirements.stream().filter(ParticipantRequirementView::required).findFirst().ifPresent(requirement -> {
            throw invalid("Process " + called + " needs someone to pick a participant for step "
                    + requirement.activityId() + " (" + requirement.activityName()
                    + "). Add an assignment rule or a fixed assignee before calling it");
        });
    }

    private static String flowableAttribute(Element element, String name) {
        for (String namespace : FLOWABLE_NAMESPACES) {
            if (element.hasAttributeNS(namespace, name)) {
                return element.getAttributeNS(namespace, name).trim();
            }
        }
        return null;
    }

    private static List<Element> flowableChildren(Element callActivity, String localName) {
        Element extensions = child(callActivity, BPMN_NS, "extensionElements");
        List<Element> result = new ArrayList<>();
        if (extensions == null) {
            return result;
        }
        for (Node node = extensions.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && FLOWABLE_NAMESPACES.contains(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element child(Element parent, String namespace, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && namespace.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
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
