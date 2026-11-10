package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Reads the Event Registry waits of a diagram: every receive task, intermediate catch event and
 * boundary event whose extensionElements hold a flowable:eventType, even a blank one. Attributes are
 * read without a namespace, as Flowable's CorrelationUtil does.
 */
public final class EventWaitBindingParser {

    static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    static final Set<String> FLOWABLE_NS = Set.of("http://flowable.org/bpmn", "http://activiti.org/bpmn");
    private static final Map<String, EventWaitBinding.Kind> KINDS = Map.of(
            "receiveTask", EventWaitBinding.Kind.RECEIVE_TASK,
            "intermediateCatchEvent", EventWaitBinding.Kind.INTERMEDIATE_CATCH,
            "boundaryEvent", EventWaitBinding.Kind.BOUNDARY);

    private EventWaitBindingParser() {
    }

    public static List<EventWaitBinding> parse(String bpmnXml) {
        return parse(document(bpmnXml));
    }

    static List<EventWaitBinding> parse(Document document) {
        List<EventWaitBinding> bindings = new ArrayList<>();
        NodeList all = document.getElementsByTagNameNS(BPMN_NS, "*");
        for (int index = 0; index < all.getLength(); index++) {
            Element element = (Element) all.item(index);
            EventWaitBinding.Kind kind = KINDS.get(element.getLocalName());
            if (kind == null) {
                continue;
            }
            Element extensions = child(element, BPMN_NS, "extensionElements");
            List<Element> types = extensions == null ? List.of() : flowableChildren(extensions, "eventType");
            if (types.isEmpty()) {
                continue;
            }
            List<EventWaitBinding.Correlation> correlations = flowableChildren(extensions, "eventCorrelationParameter")
                    .stream()
                    .map(parameter -> new EventWaitBinding.Correlation(
                            parameter.getAttribute("name"), parameter.getAttribute("value")))
                    .toList();
            List<EventWaitBinding.Output> outputs = flowableChildren(extensions, "eventOutParameter").stream()
                    .map(parameter -> new EventWaitBinding.Output(parameter.getAttribute("source"),
                            parameter.getAttribute("target"),
                            "true".equalsIgnoreCase(parameter.getAttribute("transient"))))
                    .toList();
            bindings.add(new EventWaitBinding(element.getAttribute("id"), kind,
                    types.getFirst().getTextContent().trim(), correlations, outputs,
                    kind != EventWaitBinding.Kind.RECEIVE_TASK && hasEventDefinition(element),
                    kind == EventWaitBinding.Kind.RECEIVE_TASK
                            && extensions.getElementsByTagNameNS("*", "agentTask").getLength() > 0,
                    kind == EventWaitBinding.Kind.BOUNDARY ? element.getAttribute("attachedToRef") : null));
        }
        return bindings;
    }

    private static boolean hasEventDefinition(Element event) {
        for (Node node = event.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && BPMN_NS.equals(child.getNamespaceURI())
                    && (child.getLocalName().endsWith("EventDefinition") || "eventDefinitionRef".equals(
                            child.getLocalName()))) {
                return true;
            }
        }
        return false;
    }

    private static Element child(Element parent, String namespace, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && namespace.equals(child.getNamespaceURI())
                    && localName.equals(child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    private static List<Element> flowableChildren(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && FLOWABLE_NS.contains(child.getNamespaceURI())
                    && localName.equals(child.getLocalName())) {
                result.add(child);
            }
        }
        return result;
    }

    static Document document(String bpmnXml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(bpmnXml)));
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "BPMN XML could not be read");
        }
    }
}
