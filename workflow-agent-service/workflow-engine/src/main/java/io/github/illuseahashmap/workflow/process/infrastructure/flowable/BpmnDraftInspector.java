package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.config.ProcessDefinitionProperties;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Checks that must hold for every stored BPMN document, draft or published. */
@Component
public class BpmnDraftInspector {

    static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    private final ProcessDefinitionProperties properties;

    public BpmnDraftInspector(ProcessDefinitionProperties properties) {
        this.properties = properties;
    }

    public void check(String key, String bpmnXml) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw invalid("Process key must match " + KEY.pattern());
        }
        int bytes = bpmnXml.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > properties.getMaxBpmnBytes()) {
            throw invalid("BPMN XML is " + bytes + " bytes, limit is " + properties.getMaxBpmnBytes() + " bytes");
        }
        NodeList processes = parse(bpmnXml).getElementsByTagNameNS(BPMN_NS, "process");
        if (processes.getLength() != 1) {
            throw invalid("BPMN XML must contain exactly one process");
        }
        String id = ((Element) processes.item(0)).getAttribute("id");
        if (!key.equals(id)) {
            throw invalid("BPMN process id must equal the process key");
        }
    }

    public Optional<String> processName(String bpmnXml) {
        NodeList processes = parse(bpmnXml).getElementsByTagNameNS(BPMN_NS, "process");
        if (processes.getLength() == 0) {
            return Optional.empty();
        }
        String name = ((Element) processes.item(0)).getAttribute("name");
        return name.isBlank() ? Optional.empty() : Optional.of(name.trim());
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
