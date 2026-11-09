package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Event registry elements on a published diagram. Until slice 5 of spec 2026-11-09 they are all
 * rejected: the event library, channels and designer panels they depend on do not exist yet.
 * A receive task after an event-based gateway is rejected for good: Flowable does not support it.
 */
@Component
public class EventRegistryDeploymentGuard {

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final Set<String> FLOWABLE_NS = Set.of("http://flowable.org/bpmn", "http://activiti.org/bpmn");
    private static final Set<String> EVENT_ELEMENTS = Set.of("eventType", "triggerEventType");

    public void check(String bpmnXml) {
        Document document = parse(bpmnXml);
        NodeList all = document.getElementsByTagNameNS("*", "*");
        for (int index = 0; index < all.getLength(); index++) {
            Element element = (Element) all.item(index);
            if (FLOWABLE_NS.contains(element.getNamespaceURI()) && EVENT_ELEMENTS.contains(element.getLocalName())) {
                Element owner = owner(element);
                String id = owner == null ? "?" : owner.getAttribute("id");
                if (owner != null && "receiveTask".equals(owner.getLocalName()) && hasAgentTask(owner)) {
                    throw invalid("Bước " + id + " không thể vừa là tác vụ Agent vừa chờ sự kiện");
                }
                throw invalid("Bước " + id + " dùng sự kiện (Event Registry), tính năng này chưa được hỗ trợ");
            }
            if (BPMN_NS.equals(element.getNamespaceURI()) && "serviceTask".equals(element.getLocalName())
                    && "send-event".equals(flowableAttribute(element, "type"))) {
                throw invalid("Bước " + element.getAttribute("id") + " gửi sự kiện, tính năng này chưa được hỗ trợ");
            }
        }
        rejectReceiveTasksAfterEventGateways(document);
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

    private static boolean hasAgentTask(Element receiveTask) {
        return receiveTask.getElementsByTagNameNS("*", "agentTask").getLength() > 0;
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
