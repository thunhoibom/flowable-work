package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.domain.ServiceTaskBinding;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Component
public class XmlServiceTaskBindingParser {

    private static final String WORKFLOW_NAMESPACE = "http://workflow-agent.local/bpmn";

    public List<ServiceTaskBinding> parse(String bpmnXml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(bpmnXml)));
            var xpath = XPathFactory.newInstance().newXPath();
            NodeList nodes = (NodeList) xpath.evaluate(
                    "//*[local-name()='serviceCall' and namespace-uri()='" + WORKFLOW_NAMESPACE + "']",
                    document, XPathConstants.NODESET);
            List<ServiceTaskBinding> bindings = new ArrayList<>();
            for (int index = 0; index < nodes.getLength(); index++) {
                Element extension = (Element) nodes.item(index);
                Node parent = extension.getParentNode();
                Node task = parent == null ? null : parent.getParentNode();
                if (!(task instanceof Element activity)) {
                    throw new IllegalArgumentException("workflow:serviceCall must belong to a BPMN task");
                }
                bindings.add(new ServiceTaskBinding(
                        activity.getAttribute("id"),
                        extension.getAttribute("serviceKey"),
                        extension.getAttribute("operationKey"),
                        extension.getAttribute("inputMapping"),
                        extension.getAttribute("outputMapping")));
            }
            return List.copyOf(bindings);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("BPMN contains invalid service call configuration", exception);
        }
    }
}
