package io.github.illuseahashmap.workflow.process.infrastructure.flowable.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.config.ScriptTaskProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** All engine-level script tests live here: Rhino's global context factory is set once per JVM. */
class WorkflowScriptTaskEngineTest {

    private static final String AMOUNT_AND_QTY = """
            <flowable:in source="amount" target="amount"/>
            <flowable:in sourceExpression="${order.qty}" target="qty"/>""";

    private static ProcessEngine engine;

    @BeforeAll
    static void start() {
        ScriptTaskProperties properties = new ScriptTaskProperties();
        properties.setMaxExecutionMillis(500L);
        StandaloneInMemProcessEngineConfiguration configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:script-task;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        configuration.addConfigurator(new WorkflowScriptConfigurator(properties));
        engine = configuration.buildProcessEngine();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @Test
    void writesTheResultOfDeclaredInputs() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("amount", 3);
        variables.put("order", Map.of("qty", 4L));

        assertThat(run("javascript", "amount * qty", AMOUNT_AND_QTY, variables)).isEqualTo(12L);
    }

    @Test
    void hidesUndeclaredVariablesAndHostObjects() {
        Map<String, Object> variables = Map.of("secret", "s", "amount", 1);

        assertThat(run("javascript",
                "[typeof secret, typeof execution, typeof Packages, typeof java].join(',')",
                "<flowable:in source=\"amount\" target=\"amount\"/>", variables))
                .isEqualTo("undefined,undefined,undefined,undefined");
    }

    @Test
    void storesObjectsAsMaps() {
        assertThat(run("javascript", "({a: 1, b: [true, 'x']})", "", Map.of()))
                .isEqualTo(Map.of("a", 1L, "b", List.of(true, "x")));
    }

    @Test
    void failsTheTransactionOnScriptErrors() {
        String key = deploy("javascript", "throw new Error('bad')", "");

        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey(key))
                .isInstanceOf(ScriptTaskException.class)
                .hasMessageContaining("bad");
        assertThat(engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey(key).count())
                .isZero();
        assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processDefinitionKey(key).count())
                .isZero();
    }

    @Test
    void blocksJavaAccess() {
        String key = deploy("javascript", "java.lang.System.exit(0)", "");

        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey(key))
                .isInstanceOf(ScriptTaskException.class);
    }

    @Test
    void stopsEndlessLoopsAndRecursion() {
        String loop = deploy("javascript", "while (true) {}", "");
        long started = System.nanoTime();
        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey(loop))
                .isInstanceOf(ScriptTaskException.class);
        assertThat((System.nanoTime() - started) / 1_000_000L).isLessThan(1000L);

        String recursion = deploy("javascript", "function f() { return f(); } f()", "");
        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey(recursion))
                .isInstanceOf(ScriptTaskException.class);
    }

    @Test
    void refusesOtherLanguagesAtRunTime() {
        String key = deploy("groovy", "1 + 1", "");

        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey(key))
                .isInstanceOf(ScriptTaskException.class)
                .hasMessageContaining("javascript");
    }

    private Object run(String language, String script, String inputs, Map<String, Object> variables) {
        String key = deploy(language, script, inputs);
        String id = engine.getRuntimeService().startProcessInstanceByKey(key, variables).getId();
        HistoricVariableInstance total = engine.getHistoryService().createHistoricVariableInstanceQuery()
                .processInstanceId(id).variableName("total").singleResult();
        return total == null ? null : total.getValue();
    }

    private String deploy(String language, String script, String inputs) {
        String key = "p" + UUID.randomUUID().toString().replace("-", "");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn" targetNamespace="test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="calc"/>
                    <scriptTask id="calc" scriptFormat="%s" flowable:resultVariable="total">
                      <extensionElements>%s</extensionElements>
                      <script><![CDATA[%s]]></script>
                    </scriptTask>
                    <sequenceFlow id="f2" sourceRef="calc" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, language, inputs, script);
        engine.getRepositoryService().createDeployment().addString(key + ".bpmn20.xml", xml).deploy();
        return key;
    }
}
