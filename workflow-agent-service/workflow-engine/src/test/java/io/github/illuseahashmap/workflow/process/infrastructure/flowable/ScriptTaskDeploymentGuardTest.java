package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.config.ScriptTaskProperties;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ScriptTaskDeploymentGuardTest {

    static final String VALID_TASK = """
            <bpmn:scriptTask id="calc" scriptFormat="javascript" flowable:resultVariable="total">
              <bpmn:extensionElements>
                <flowable:in source="amount" target="amount"/>
                <flowable:in sourceExpression="${order.qty}" target="qty"/>
              </bpmn:extensionElements>
              <bpmn:script>amount * qty</bpmn:script>
            </bpmn:scriptTask>""";

    static String bpmn(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:flowable="http://flowable.org/bpmn"
                                  xmlns:activiti="http://activiti.org/bpmn" targetNamespace="test">
                  <bpmn:process id="p" isExecutable="true">%s</bpmn:process>
                </bpmn:definitions>
                """.formatted(body);
    }

    private static String task(String attributes, String extensions, String script) {
        return """
                <bpmn:scriptTask id="s" scriptFormat="javascript" %s>
                  <bpmn:extensionElements>%s</bpmn:extensionElements>
                  <bpmn:script>%s</bpmn:script>
                </bpmn:scriptTask>""".formatted(attributes, extensions, script);
    }

    private static ScriptTaskDeploymentGuard guard(boolean enabled) {
        ScriptTaskProperties properties = new ScriptTaskProperties();
        properties.setEnabled(enabled);
        properties.setMaxScriptLength(50);
        properties.setMaxInputs(2);
        return new ScriptTaskDeploymentGuard(properties);
    }

    @Test
    void acceptsValidScriptTaskWhenEnabled() {
        assertThatCode(() -> guard(true).check(bpmn(VALID_TASK))).doesNotThrowAnyException();
    }

    @Test
    void acceptsDocumentsWithoutScriptsWhenDisabled() {
        String body = """
                <bpmn:userTask id="u">
                  <bpmn:extensionElements>
                    <flowable:taskListener event="create" class="com.example.DescriptionListener"/>
                  </bpmn:extensionElements>
                </bpmn:userTask>""";
        assertThatCode(() -> guard(false).check(bpmn(body))).doesNotThrowAnyException();
    }

    @Test
    void rejectsScriptTasksWhenDisabled() {
        assertThatThrownBy(() -> guard(false).check(bpmn(VALID_TASK)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("disabled");
    }

    static Stream<Arguments> unsafeDocuments() {
        return Stream.of(
                Arguments.of("groovy", bpmn(VALID_TASK.replace("javascript", "groovy")), "scriptFormat javascript"),
                Arguments.of("blank script", bpmn(task("", "", "   ")), "empty script"),
                Arguments.of("long script", bpmn(task("", "", "x".repeat(51))), "longer than"),
                Arguments.of("auto store", bpmn(task("flowable:autoStoreVariables=\"true\"", "", "1")),
                        "autoStoreVariables"),
                Arguments.of("auto store activiti", bpmn(task("activiti:autoStoreVariables=\"true\"", "", "1")),
                        "autoStoreVariables"),
                Arguments.of("bad result", bpmn(task("flowable:resultVariable=\"1bad\"", "", "1")),
                        "result variable"),
                Arguments.of("internal result", bpmn(task("flowable:resultVariable=\"tenantId\"", "", "1")),
                        "result variable"),
                Arguments.of("participant result",
                        bpmn(task("flowable:resultVariableName=\"a_assignee\"", "", "1")), "result variable"),
                Arguments.of("too many inputs", bpmn(task("", """
                        <flowable:in source="a" target="a"/>
                        <flowable:in source="b" target="b"/>
                        <flowable:in source="c" target="c"/>""", "1")), "more than"),
                Arguments.of("bad target", bpmn(task("", "<flowable:in source=\"a\" target=\"a-b\"/>", "1")),
                        "name is invalid"),
                Arguments.of("duplicate target", bpmn(task("",
                        "<flowable:in source=\"a\" target=\"x\"/><flowable:in source=\"b\" target=\"x\"/>", "1")),
                        "duplicated"),
                Arguments.of("both sources",
                        bpmn(task("", "<flowable:in source=\"a\" sourceExpression=\"${a}\" target=\"x\"/>", "1")),
                        "exactly one"),
                Arguments.of("no source", bpmn(task("", "<flowable:in target=\"x\"/>", "1")), "exactly one"),
                Arguments.of("bad source", bpmn(task("", "<flowable:in source=\"a.b\" target=\"x\"/>", "1")),
                        "not a variable name"),
                Arguments.of("plain expression",
                        bpmn(task("", "<flowable:in sourceExpression=\"order.qty\" target=\"x\"/>", "1")),
                        "must contain"),
                Arguments.of("out parameter", bpmn(task("", "<flowable:out source=\"x\" target=\"y\"/>", "1")),
                        "flowable:out"),
                Arguments.of("script listener type", bpmn("""
                        <bpmn:userTask id="u"><bpmn:extensionElements>
                          <flowable:executionListener event="start" type="script"/>
                        </bpmn:extensionElements></bpmn:userTask>"""), "Script listeners"),
                Arguments.of("script element in listener", bpmn("""
                        <bpmn:userTask id="u"><bpmn:extensionElements>
                          <flowable:taskListener event="create">
                            <flowable:script>1</flowable:script>
                          </flowable:taskListener>
                        </bpmn:extensionElements></bpmn:userTask>"""), "body of a script task"),
                Arguments.of("script listener class", bpmn("""
                        <bpmn:userTask id="u"><bpmn:extensionElements>
                          <flowable:executionListener event="start"
                              class="org.flowable.engine.impl.bpmn.listener.ScriptExecutionListener"/>
                        </bpmn:extensionElements></bpmn:userTask>"""), "Script listeners"),
                Arguments.of("secure listener class", bpmn("""
                        <bpmn:userTask id="u"><bpmn:extensionElements>
                          <flowable:taskListener event="create"
                              class="org.flowable.scripting.secure.listener.SecureJavascriptTaskListener"/>
                        </bpmn:extensionElements></bpmn:userTask>"""), "Script listeners"),
                Arguments.of("condition language", bpmn("""
                        <bpmn:sequenceFlow id="f" sourceRef="a" targetRef="b">
                          <bpmn:conditionExpression language="groovy">x</bpmn:conditionExpression>
                        </bpmn:sequenceFlow>"""), "Script languages"),
                Arguments.of("stray scriptFormat", bpmn("<bpmn:serviceTask id=\"x\" scriptFormat=\"javascript\"/>"),
                        "only allowed on a script task"),
                Arguments.of("doctype", "<!DOCTYPE x [<!ENTITY e \"e\">]>"
                        + bpmn(VALID_TASK).replace("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", ""),
                        "could not be read"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeDocuments")
    void rejectsUnsafeDocuments(String name, String xml, String reason) {
        assertThatThrownBy(() -> guard(true).check(xml))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(reason);
    }
}
