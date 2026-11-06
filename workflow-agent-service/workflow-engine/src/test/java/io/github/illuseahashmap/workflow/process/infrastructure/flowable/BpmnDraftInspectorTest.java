package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.config.ProcessDefinitionProperties;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

class BpmnDraftInspectorTest {

    static String bpmn(String processes) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                %s
                </definitions>""".formatted(processes);
    }

    private final BpmnDraftInspector inspector = new BpmnDraftInspector(new ProcessDefinitionProperties());

    @Test
    void acceptsOneProcessWithTheKey() {
        inspector.check("order", bpmn("<process id=\"order\" isExecutable=\"true\"/>"));
    }

    @Test
    void rejectsAnInvalidKey() {
        assertThatThrownBy(() -> inspector.check("1order", bpmn("<process id=\"1order\"/>")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("key");
    }

    @Test
    void rejectsAMismatchedProcessId() {
        assertThatThrownBy(() -> inspector.check("order", bpmn("<process id=\"other\"/>")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("process id");
    }

    @Test
    void rejectsSeveralProcesses() {
        assertThatThrownBy(() -> inspector.check("order",
                bpmn("<process id=\"order\"/><process id=\"order2\"/>")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("exactly one");
    }

    @Test
    void rejectsADoctype() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE definitions [<!ENTITY e "x">]>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <process id="order" name="&e;"/>
                </definitions>""";

        assertThatThrownBy(() -> inspector.check("order", xml))
                .isInstanceOf(BusinessException.class).hasMessageContaining("could not be read");
    }

    @Test
    void rejectsAnOversizedDocument() {
        ProcessDefinitionProperties properties = new ProcessDefinitionProperties();
        properties.setMaxBpmnBytes(64);
        BpmnDraftInspector small = new BpmnDraftInspector(properties);

        assertThatThrownBy(() -> small.check("order", bpmn("<process id=\"order\"/>")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("bytes");
    }

    @Test
    void readsTheProcessName() {
        assertThat(inspector.processName(bpmn("<process id=\"order\" name=\"Đơn hàng\"/>")))
                .contains("Đơn hàng");
        assertThat(inspector.processName(bpmn("<process id=\"order\"/>"))).isEmpty();
    }
}
