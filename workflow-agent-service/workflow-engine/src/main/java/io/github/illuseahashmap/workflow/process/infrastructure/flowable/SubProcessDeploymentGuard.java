package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.config.SubProcessProperties;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Rejects subprocess shapes the platform does not support before they reach the engine.
 * Supported: embedded subprocesses with timer/error boundaries, and error/timer event subprocesses.
 */
@Component
public class SubProcessDeploymentGuard {

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final Set<String> UNSUPPORTED = Set.of("adHocSubProcess", "transaction");
    private static final Set<String> EVENT_SUBPROCESS_STARTS = Set.of("errorEventDefinition", "timerEventDefinition");
    private static final Set<String> SUBPROCESS_BOUNDARIES = Set.of("errorEventDefinition", "timerEventDefinition");

    private final SubProcessProperties properties;

    public SubProcessDeploymentGuard(SubProcessProperties properties) {
        this.properties = properties;
    }

    public void check(String bpmnXml) {
        Document document = parse(bpmnXml);
        for (String name : UNSUPPORTED) {
            if (document.getElementsByTagNameNS(BPMN_NS, name).getLength() > 0) {
                throw invalid("BPMN element " + name + " is not supported");
            }
        }
        List<Element> subProcesses = elements(document, "subProcess");
        if (subProcesses.isEmpty()) {
            return;
        }
        Set<String> flowEnds = sequenceFlowEnds(document);
        Set<String> embeddedIds = new HashSet<>();
        for (Element subProcess : subProcesses) {
            String id = subProcess.getAttribute("id");
            if ("true".equals(subProcess.getAttribute("triggeredByEvent"))) {
                checkEventSubProcess(subProcess, id, flowEnds);
            } else {
                checkEmbedded(subProcess, id);
                embeddedIds.add(id);
            }
            if (depth(subProcess) > properties.getMaxNestingDepth()) {
                throw invalid("Subprocess " + id + " is nested deeper than " + properties.getMaxNestingDepth());
            }
        }
        for (Element boundary : elements(document, "boundaryEvent")) {
            List<String> definitions = eventDefinitions(boundary);
            boolean eventRegistry = definitions.isEmpty() && hasEventType(boundary);
            if (embeddedIds.contains(boundary.getAttribute("attachedToRef")) && !eventRegistry
                    && definitions.stream().noneMatch(SUBPROCESS_BOUNDARIES::contains)) {
                throw invalid("Boundary event " + boundary.getAttribute("id")
                        + " on a subprocess must be a timer, error or Event Registry event");
            }
        }
    }

    private static void checkEmbedded(Element subProcess, String id) {
        List<Element> starts = children(subProcess, "startEvent");
        if (starts.size() != 1 || !eventDefinitions(starts.getFirst()).isEmpty()) {
            throw invalid("Subprocess " + id + " needs exactly one start event without a trigger");
        }
        if (children(subProcess, "endEvent").isEmpty()) {
            throw invalid("Subprocess " + id + " needs at least one end event");
        }
    }

    private static void checkEventSubProcess(Element subProcess, String id, Set<String> flowEnds) {
        if (flowEnds.contains(id)) {
            throw invalid("Event subprocess " + id + " cannot have an incoming or outgoing sequence flow");
        }
        List<Element> starts = children(subProcess, "startEvent");
        if (starts.size() != 1
                || eventDefinitions(starts.getFirst()).stream().noneMatch(EVENT_SUBPROCESS_STARTS::contains)) {
            throw invalid("Event subprocess " + id + " needs exactly one error or timer start event");
        }
    }

    /** Number of subProcess elements from this one up to the process, inclusive. */
    private static int depth(Element subProcess) {
        int depth = 0;
        for (Node node = subProcess; node instanceof Element element; node = node.getParentNode()) {
            if (BPMN_NS.equals(element.getNamespaceURI()) && "subProcess".equals(element.getLocalName())) {
                depth++;
            }
        }
        return depth;
    }

    private static Set<String> sequenceFlowEnds(Document document) {
        Set<String> ends = new HashSet<>();
        for (Element flow : elements(document, "sequenceFlow")) {
            ends.add(flow.getAttribute("sourceRef"));
            ends.add(flow.getAttribute("targetRef"));
        }
        return ends;
    }

    private static List<String> eventDefinitions(Element event) {
        List<String> names = new ArrayList<>();
        for (Node child = event.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && BPMN_NS.equals(element.getNamespaceURI())
                    && element.getLocalName().endsWith("EventDefinition")) {
                names.add(element.getLocalName());
            }
        }
        return names;
    }

    /** A flowable:eventType inside the event's extensionElements: an Event Registry boundary (slice 5). */
    private static boolean hasEventType(Element event) {
        for (Element extensions : children(event, "extensionElements")) {
            for (Node child = extensions.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element element && "eventType".equals(element.getLocalName())
                        && EventWaitBindingParser.FLOWABLE_NS.contains(element.getNamespaceURI())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && BPMN_NS.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static List<Element> elements(Document document, String localName) {
        NodeList nodes = document.getElementsByTagNameNS(BPMN_NS, localName);
        List<Element> result = new ArrayList<>(nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) {
            result.add((Element) nodes.item(index));
        }
        return result;
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
