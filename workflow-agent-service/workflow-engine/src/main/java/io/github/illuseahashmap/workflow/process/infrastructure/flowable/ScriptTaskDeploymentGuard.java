package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.config.ScriptTaskProperties;
import io.github.illuseahashmap.workflow.process.application.ProcessVariablePolicy;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Rejects script content the sandbox does not cover, before Flowable sees the deployment. */
@Component
public class ScriptTaskDeploymentGuard {

    private static final String BPMN_NAMESPACE = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    /** Flowable reads its extension attributes from both namespaces. */
    private static final List<String> FLOWABLE_NAMESPACES = List.of(
            "http://flowable.org/bpmn", "http://activiti.org/bpmn");
    private static final Pattern VARIABLE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,127}$");
    private static final Pattern TARGET = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]{0,127}$");
    private static final Pattern SCRIPT_LISTENER_CLASS = Pattern.compile(
            "^org\\.flowable\\.(scripting\\..*|.*Script[A-Za-z]*Listener)$");

    private final ScriptTaskProperties properties;

    public ScriptTaskDeploymentGuard(ScriptTaskProperties properties) {
        this.properties = properties;
    }

    public void check(String bpmnXml) {
        NodeList elements = parse(bpmnXml).getElementsByTagNameNS("*", "*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            checkAttributes(element);
            if (isBpmn(element, "scriptTask")) {
                checkScriptTask(element);
            } else if ("script".equals(element.getLocalName()) && !isScriptTaskBody(element)) {
                throw invalid("Scripts are only allowed as the body of a script task");
            } else if (isFlowable(element, "executionListener") || isFlowable(element, "taskListener")) {
                checkListener(element);
            }
        }
    }

    private static void checkAttributes(Element element) {
        NamedNodeMap attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            String name = attribute.getLocalName() == null ? attribute.getNodeName() : attribute.getLocalName();
            if ("language".equals(name)) {
                throw invalid("Script languages are not allowed on " + element.getLocalName());
            }
            if ("scriptFormat".equals(name) && !isBpmn(element, "scriptTask")) {
                throw invalid("scriptFormat is only allowed on a script task");
            }
        }
    }

    private void checkScriptTask(Element task) {
        String id = task.getAttribute("id");
        if (!properties.isEnabled()) {
            throw invalid("Script tasks are disabled: " + id);
        }
        if (!"javascript".equals(task.getAttribute("scriptFormat"))) {
            throw invalid("Script task must use scriptFormat javascript: " + id);
        }
        List<Element> bodies = children(task, BPMN_NAMESPACE, "script");
        if (bodies.size() != 1) {
            throw invalid("Script task must have exactly one script: " + id);
        }
        String script = bodies.get(0).getTextContent();
        if (script == null || script.isBlank()) {
            throw invalid("Script task has an empty script: " + id);
        }
        if (script.length() > properties.getMaxScriptLength()) {
            throw invalid("Script is longer than " + properties.getMaxScriptLength() + " characters: " + id);
        }
        String autoStore = flowableAttribute(task, "autoStoreVariables");
        if (autoStore != null && !"false".equals(autoStore)) {
            throw invalid("autoStoreVariables is not allowed: " + id);
        }
        for (String name : List.of("resultVariable", "resultVariableName")) {
            String variable = flowableAttribute(task, name);
            if (variable != null && (!VARIABLE.matcher(variable).matches()
                    || ProcessVariablePolicy.isInternalVariable(variable))) {
                throw invalid("Script task result variable is not allowed: " + id);
            }
        }
        checkInputs(task, id);
    }

    private void checkInputs(Element task, String id) {
        List<Element> inputs = new ArrayList<>();
        for (Element extensions : children(task, BPMN_NAMESPACE, "extensionElements")) {
            for (Element child : children(extensions, null, null)) {
                if (isFlowable(child, "out")) {
                    throw invalid("Script task does not support flowable:out: " + id);
                }
                if (isFlowable(child, "in")) {
                    inputs.add(child);
                }
            }
        }
        if (inputs.size() > properties.getMaxInputs()) {
            throw invalid("Script task has more than " + properties.getMaxInputs() + " inputs: " + id);
        }
        Set<String> targets = new HashSet<>();
        for (Element input : inputs) {
            String target = input.getAttribute("target");
            if (!TARGET.matcher(target).matches()) {
                throw invalid("Script input name is invalid on " + id);
            }
            if (!targets.add(target)) {
                throw invalid("Script input name is duplicated: " + target);
            }
            String source = input.getAttribute("source");
            String expression = input.getAttribute("sourceExpression");
            boolean hasSource = !source.isBlank();
            boolean hasExpression = !expression.isBlank();
            if (hasSource == hasExpression) {
                throw invalid("Script input needs exactly one of source or sourceExpression: " + target);
            }
            if (hasSource && !VARIABLE.matcher(source).matches()) {
                throw invalid("Script input source is not a variable name: " + target);
            }
            if (hasExpression && !(expression.contains("${") || expression.contains("#{"))) {
                throw invalid("Script input expression must contain ${ or #{: " + target);
            }
        }
    }

    private static void checkListener(Element listener) {
        if ("script".equalsIgnoreCase(listener.getAttribute("type").trim())) {
            throw invalid("Script listeners are not supported");
        }
        String className = listener.getAttribute("class").trim();
        if (SCRIPT_LISTENER_CLASS.matcher(className).matches()) {
            throw invalid("Script listeners are not supported: " + className);
        }
    }

    private static boolean isScriptTaskBody(Element element) {
        return isBpmn(element, "script")
                && element.getParentNode() instanceof Element parent
                && isBpmn(parent, "scriptTask");
    }

    private static boolean isBpmn(Element element, String localName) {
        return BPMN_NAMESPACE.equals(element.getNamespaceURI()) && localName.equals(element.getLocalName());
    }

    private static boolean isFlowable(Element element, String localName) {
        return FLOWABLE_NAMESPACES.contains(element.getNamespaceURI()) && localName.equals(element.getLocalName());
    }

    private static String flowableAttribute(Element element, String name) {
        for (String namespace : FLOWABLE_NAMESPACES) {
            String value = element.getAttributeNS(namespace, name).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String namespace, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element child
                    && (localName == null
                    || (namespace.equals(child.getNamespaceURI()) && localName.equals(child.getLocalName())))) {
                result.add(child);
            }
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
