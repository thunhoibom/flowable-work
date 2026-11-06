# Script Task Node Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A designer can add a Flowable 8 `scriptTask` that runs a short JavaScript snippet in a Rhino sandbox, reading only declared inputs and writing one result variable.

**Spec:** `docs/superpowers/specs/2026-11-04-script-task-node-design.md`

**Architecture:** `flowable-secure-javascript` supplies the Rhino context factory (time, memory, stack limits) and the class shutter. Our own parse handler replaces every `scriptTask` behavior with `WorkflowScriptTaskActivityBehavior`. It evaluates `<flowable:in>`, converts values to plain JavaScript, runs the script in a safe scope, and converts the result back. `ScriptTaskDeploymentGuard` rejects unsafe BPMN before `createDeployment`. The web designer gets a palette entry, a panel, and moddle types.

**Tech Stack:** Java 25, Spring Boot 4.1, Flowable 8.0.0, Rhino 1.7.14.1 (through `flowable-secure-javascript`), JUnit 5, AssertJ, Mockito, H2 (test only), Vue 3, Vitest, Element Plus, bpmn-js.

## Global Constraints

- Flowable stays `8.0.0`. Do not add Groovy, Nashorn, GraalJS, or any JSR-223 engine.
- `scriptFormat` is exactly `javascript`.
- Every `scriptTask` runs through `WorkflowScriptTaskActivityBehavior`, even when `workflow.script-task.enabled=false`. The flag only gates deployment.
- The script scope comes from `initSafeStandardObjects()`. It contains only the declared `<flowable:in>` targets. There is no `execution`, `task`, bean, `Packages`, or `java`.
- `SecureJavascriptConfigurator` runs with `enableClassWhiteListing=true`, an empty whitelist, `enableAccessToBeans=false`, and `scriptOptimizationLevel=-1`.
- Language level is `Context.VERSION_ES6`.
- Input conversion: `null`, `String`, `Character`, `Boolean`, `Integer`, `Short`, `Byte`, `Long` within ±2^53, finite `Float`/`Double`, `Map` with string keys, `List`, `Object[]`. Anything else throws.
- Result conversion: `undefined`/`null` → `null`; string → `String`; boolean → `Boolean`; integral finite number within ±2^53 → `Long`; other finite number → `Double`; `NativeArray` → `ArrayList`; `NativeObject` → `LinkedHashMap`. `NaN`, infinity, functions, Date, RegExp, Java objects, and nesting deeper than 32 throw.
- Every failure in the script is `ScriptTaskException extends FlowableException`. It is not a `BpmnError`. Its message is `Script task <id> failed: <reason>`, with the reason cut to 500 characters. It never contains variable values.
- The deploy guard throws `BusinessException(ErrorCode.BAD_REQUEST, ...)` and runs before the Agent validator and before `createDeployment`.
- The guard treats `http://flowable.org/bpmn` and `http://activiti.org/bpmn` as the same namespace, because Flowable reads both.
- Variable name pattern `^[A-Za-z][A-Za-z0-9_]{0,127}$`. Input target pattern `^[A-Za-z_$][A-Za-z0-9_$]{0,127}$`.
- Properties prefix `workflow.script-task`, with defaults `enabled=false`, `max-script-length=20000`, `max-inputs=32`, `max-execution-millis=2000`, `max-memory-bytes=8388608`, `max-stack-depth=64`, `instruction-check-interval=100`.
- `ContextFactory.initGlobal` runs once per JVM. Keep every engine-level script test in one test class.
- The deploy endpoint roles do not change.
- Web: the palette entry shows only when `VITE_SCRIPT_TASKS_ENABLED=true`. Saving with warnings still writes the BPMN.
- No script listeners, no script conditions, no `BpmnError` from scripts, no browser end-to-end test.

Commands for Java tasks run from `workflow-agent-service`. Web commands run from `workflow-agent-web`.

---

### Task 1: Dependencies and properties

**Files:**
- Modify: `workflow-agent-service/pom.xml`
- Modify: `workflow-agent-service/workflow-engine/pom.xml`
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/config/ScriptTaskProperties.java`
- Modify: `workflow-agent-service/workflow-boot/src/main/resources/application.yml`
- Modify: `workflow-agent-service/.env.example`

**Interfaces:**
- Produces: `ScriptTaskProperties` with getters and setters for `enabled`, `maxScriptLength`, `maxInputs`, `maxExecutionMillis`, `maxMemoryBytes`, `maxStackDepth`, `instructionCheckInterval`.

- [ ] **Step 1: Manage the secure JavaScript module**

In `workflow-agent-service/pom.xml`, inside `<dependencyManagement><dependencies>`, after the `flowable-spring-boot-starter-process` entry:

```xml
            <dependency>
                <groupId>org.flowable</groupId>
                <artifactId>flowable-secure-javascript</artifactId>
                <version>${flowable.version}</version>
            </dependency>
```

- [ ] **Step 2: Add the module and H2 to the engine**

In `workflow-agent-service/workflow-engine/pom.xml`, after the `flowable-spring-boot-starter-process` dependency:

```xml
        <dependency>
            <groupId>org.flowable</groupId>
            <artifactId>flowable-secure-javascript</artifactId>
        </dependency>
```

Before the `spring-boot-starter-test` dependency:

```xml
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: Check the dependency tree**

Run: `mvn -pl workflow-engine dependency:tree -Dincludes=org.mozilla,org.apache.groovy,org.openjdk.nashorn`

Expected: `org.mozilla:rhino:jar:1.7.14.1` only. No Groovy and no Nashorn.

- [ ] **Step 4: Write the properties class**

```java
package io.github.illuseahashmap.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits for the sandboxed JavaScript script task. */
@ConfigurationProperties("workflow.script-task")
public class ScriptTaskProperties {

    /** Whether deployments may contain a script task. Deployed script tasks always run sandboxed. */
    private boolean enabled = false;
    private int maxScriptLength = 20000;
    private int maxInputs = 32;
    private long maxExecutionMillis = 2000L;
    private long maxMemoryBytes = 8388608L;
    private int maxStackDepth = 64;
    private int instructionCheckInterval = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxScriptLength() {
        return maxScriptLength;
    }

    public void setMaxScriptLength(int maxScriptLength) {
        this.maxScriptLength = maxScriptLength;
    }

    public int getMaxInputs() {
        return maxInputs;
    }

    public void setMaxInputs(int maxInputs) {
        this.maxInputs = maxInputs;
    }

    public long getMaxExecutionMillis() {
        return maxExecutionMillis;
    }

    public void setMaxExecutionMillis(long maxExecutionMillis) {
        this.maxExecutionMillis = maxExecutionMillis;
    }

    public long getMaxMemoryBytes() {
        return maxMemoryBytes;
    }

    public void setMaxMemoryBytes(long maxMemoryBytes) {
        this.maxMemoryBytes = maxMemoryBytes;
    }

    public int getMaxStackDepth() {
        return maxStackDepth;
    }

    public void setMaxStackDepth(int maxStackDepth) {
        this.maxStackDepth = maxStackDepth;
    }

    public int getInstructionCheckInterval() {
        return instructionCheckInterval;
    }

    public void setInstructionCheckInterval(int instructionCheckInterval) {
        this.instructionCheckInterval = instructionCheckInterval;
    }
}
```

`WorkflowAgentServiceApplication` already scans `io.github.illuseahashmap.workflow` for configuration properties.

- [ ] **Step 5: Add configuration**

In `application.yml`, under `workflow:`, directly after the `services:` block (`environment: ${WORKFLOW_SERVICES_ENVIRONMENT:default}`):

```yaml
  script-task:
    enabled: ${WORKFLOW_SCRIPT_TASK_ENABLED:false}
    max-script-length: ${WORKFLOW_SCRIPT_TASK_MAX_SCRIPT_LENGTH:20000}
    max-inputs: ${WORKFLOW_SCRIPT_TASK_MAX_INPUTS:32}
    max-execution-millis: ${WORKFLOW_SCRIPT_TASK_MAX_EXECUTION_MILLIS:2000}
    max-memory-bytes: ${WORKFLOW_SCRIPT_TASK_MAX_MEMORY_BYTES:8388608}
    max-stack-depth: ${WORKFLOW_SCRIPT_TASK_MAX_STACK_DEPTH:64}
    instruction-check-interval: ${WORKFLOW_SCRIPT_TASK_INSTRUCTION_CHECK_INTERVAL:100}
```

Append to `workflow-agent-service/.env.example`:

```
# Allow deployments that contain a sandboxed JavaScript script task.
WORKFLOW_SCRIPT_TASK_ENABLED=false
```

- [ ] **Step 6: Compile**

Run: `mvn -pl workflow-engine -am compile -q`

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add workflow-agent-service/pom.xml workflow-agent-service/workflow-engine/pom.xml workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/config/ScriptTaskProperties.java workflow-agent-service/workflow-boot/src/main/resources/application.yml workflow-agent-service/.env.example
git commit -m "build: add Flowable secure JavaScript and script task settings"
```

---

### Task 2: Value conversion and sandboxed evaluation

**Files:**
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/script/ScriptTaskException.java`
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/script/ScriptValues.java`
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/script/SandboxedJavascript.java`
- Test: `workflow-agent-service/workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/script/SandboxedJavascriptTest.java`

**Interfaces:**
- Produces: `SandboxedJavascript.evaluate(String taskId, String script, Map<String, Object> inputs)` returns the converted result or throws `ScriptTaskException`.
- Produces: `ScriptTaskException(String taskId, String reason)` and `ScriptTaskException(String taskId, String reason, Throwable cause)`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxedJavascriptTest {

    private Object eval(String script, Map<String, Object> inputs) {
        return SandboxedJavascript.evaluate("calc", script, inputs);
    }

    @Test
    void convertsInputsToPlainJavascript() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("s", "x");
        inputs.put("b", true);
        inputs.put("i", 1);
        inputs.put("l", 2L);
        inputs.put("d", 1.5);
        inputs.put("m", Map.of("k", "v"));
        inputs.put("a", List.of(1, 2));
        inputs.put("n", null);

        assertThat(eval("[s, b, i, l, d, m.k, a[1], n === null, typeof s].join('|')", inputs))
                .isEqualTo("x|true|1|2|1.5|v|2|true|string");
    }

    @Test
    void convertsResultsToJava() {
        assertThat(eval("42", Map.of())).isEqualTo(42L);
        assertThat(eval("1.5", Map.of())).isEqualTo(1.5);
        assertThat(eval("'x' + 'y'", Map.of())).isEqualTo("xy");
        assertThat(eval("true", Map.of())).isEqualTo(true);
        assertThat(eval("undefined", Map.of())).isNull();
        assertThat(eval("null", Map.of())).isNull();

        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("b", null);
        List<Object> list = new ArrayList<>();
        list.add(1L);
        list.add(inner);
        assertThat(eval("({a: [1, {b: null}]})", Map.of())).isEqualTo(Map.of("a", list));
        assertThat(eval("[1, 2].map(x => x * 2)", Map.of())).isEqualTo(List.of(2L, 4L));
    }

    @Test
    void rejectsValuesThatCannotCrossTheBoundary() {
        assertThatThrownBy(() -> eval("l", Map.of("l", 9007199254740993L)))
                .isInstanceOf(ScriptTaskException.class).hasMessageContaining("l");
        assertThatThrownBy(() -> eval("d", Map.of("d", new Date())))
                .isInstanceOf(ScriptTaskException.class).hasMessageContaining("java.util.Date");
        assertThatThrownBy(() -> eval("NaN", Map.of())).isInstanceOf(ScriptTaskException.class);
        assertThatThrownBy(() -> eval("(function () {})", Map.of())).isInstanceOf(ScriptTaskException.class);
        assertThatThrownBy(() -> eval("new Date()", Map.of())).isInstanceOf(ScriptTaskException.class);
    }

    @Test
    void limitsNestingToThirtyTwoLevels() {
        String nested = "var a = 1; for (var i = 0; i < %d; i++) { a = [a]; } a";
        assertThat(eval(nested.formatted(32), Map.of())).isInstanceOf(List.class);
        assertThatThrownBy(() -> eval(nested.formatted(33), Map.of()))
                .isInstanceOf(ScriptTaskException.class).hasMessageContaining("deeper");
    }

    @Test
    void hidesHostObjectsAndReportsScriptErrors() {
        assertThat(eval("[typeof Packages, typeof java, typeof execution].join(',')", Map.of()))
                .isEqualTo("undefined,undefined,undefined");
        assertThatThrownBy(() -> eval("throw new Error('bad')", Map.of()))
                .isInstanceOf(ScriptTaskException.class)
                .hasMessageStartingWith("Script task calc failed: ")
                .hasMessageContaining("bad");
        assertThatThrownBy(() -> eval("java.lang.System.exit(0)", Map.of()))
                .isInstanceOf(ScriptTaskException.class);
        assertThatThrownBy(() -> eval("(", Map.of())).isInstanceOf(ScriptTaskException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl workflow-engine test -Dtest=SandboxedJavascriptTest`

Expected: FAIL because `SandboxedJavascript` does not exist.

- [ ] **Step 3: Write the minimal implementation**

`ScriptTaskException.java`:

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.script;

import org.flowable.common.engine.api.FlowableException;

/** Technical failure of a sandboxed script task. Never a BPMN error; the process transaction rolls back. */
public class ScriptTaskException extends FlowableException {

    private static final long serialVersionUID = 1L;

    public ScriptTaskException(String taskId, String reason) {
        super("Script task " + taskId + " failed: " + reason);
    }

    public ScriptTaskException(String taskId, String reason, Throwable cause) {
        super("Script task " + taskId + " failed: " + reason, cause);
    }
}
```

`ScriptValues.java`:

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

/** Moves values across the Java/JavaScript boundary without exposing any Java object to the script. */
final class ScriptValues {

    static final int MAX_DEPTH = 32;
    private static final long MAX_SAFE_INTEGER = 9007199254740992L;

    private ScriptValues() {
    }

    static Object toJs(Context cx, Scriptable scope, String path, Object value) {
        return toJs(cx, scope, path, value, 0);
    }

    static Object toJava(Object value) {
        return toJava(value, "result", 0);
    }

    private static Object toJs(Context cx, Scriptable scope, String path, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(path + " is nested deeper than " + MAX_DEPTH + " levels");
        }
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Long number) {
            if (number > MAX_SAFE_INTEGER || number < -MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException(path + " is outside the safe integer range");
            }
            return number.doubleValue();
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException(path + " is not a finite number");
            }
            return number;
        }
        if (value instanceof Map<?, ?> map) {
            Scriptable object = cx.newObject(scope);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(path + " has a non-string key");
                }
                ScriptableObject.putProperty(object, key, toJs(cx, scope, path + "." + key, entry.getValue(), depth + 1));
            }
            return object;
        }
        if (value instanceof List<?> list) {
            return array(cx, scope, path, list.toArray(), depth);
        }
        if (value instanceof Object[] items) {
            return array(cx, scope, path, items, depth);
        }
        throw new IllegalArgumentException(path + " has unsupported type " + value.getClass().getName());
    }

    private static Scriptable array(Context cx, Scriptable scope, String path, Object[] items, int depth) {
        Object[] converted = new Object[items.length];
        for (int index = 0; index < items.length; index++) {
            converted[index] = toJs(cx, scope, path + "[" + index + "]", items[index], depth + 1);
        }
        return cx.newArray(scope, converted);
    }

    private static Object toJava(Object value, String path, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(path + " is nested deeper than " + MAX_DEPTH + " levels");
        }
        if (value == null || value instanceof Undefined || value == Scriptable.NOT_FOUND) {
            return null;
        }
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            double result = number.doubleValue();
            if (!Double.isFinite(result)) {
                throw new IllegalArgumentException(path + " is not a finite number");
            }
            if (result == Math.rint(result) && Math.abs(result) <= MAX_SAFE_INTEGER) {
                return (long) result;
            }
            return result;
        }
        if (value instanceof NativeArray array) {
            List<Object> items = new ArrayList<>();
            for (int index = 0; index < array.getLength(); index++) {
                items.add(toJava(array.get(index, array), path + "[" + index + "]", depth + 1));
            }
            return items;
        }
        if (value instanceof NativeObject object) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Object id : object.getIds()) {
                String key = String.valueOf(id);
                Object field = id instanceof Integer index ? object.get(index, object) : object.get(key, object);
                fields.put(key, toJava(field, path + "." + key, depth + 1));
            }
            return fields;
        }
        String type = value instanceof Scriptable scriptable ? scriptable.getClassName() : value.getClass().getName();
        throw new IllegalArgumentException(path + " has unsupported type " + type);
    }
}
```

`SandboxedJavascript.java`:

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.script;

import java.util.Map;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptableObject;

/** Runs one script in a fresh safe scope that holds only the given inputs. */
public final class SandboxedJavascript {

    private static final int MAX_REASON_LENGTH = 500;

    private SandboxedJavascript() {
    }

    public static Object evaluate(String taskId, String script, Map<String, Object> inputs) {
        Context cx = ContextFactory.getGlobal().enterContext();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            ScriptableObject scope = cx.initSafeStandardObjects();
            for (Map.Entry<String, Object> input : inputs.entrySet()) {
                ScriptableObject.putProperty(scope, input.getKey(),
                        ScriptValues.toJs(cx, scope, input.getKey(), input.getValue()));
            }
            return ScriptValues.toJava(cx.evaluateString(scope, script, taskId, 1, null));
        } catch (RhinoException exception) {
            throw new ScriptTaskException(taskId, shorten(exception.details()), exception);
        } catch (IllegalArgumentException exception) {
            throw new ScriptTaskException(taskId, shorten(exception.getMessage()), exception);
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Error error) {
            // SecureScriptContextFactory signals time and memory limits with java.lang.Error.
            throw new ScriptTaskException(taskId, shorten(error.getMessage()), error);
        } finally {
            Context.exit();
        }
    }

    private static String shorten(String reason) {
        String text = reason == null ? "unknown error" : reason;
        return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH);
    }
}
```

The inputs map comes from `<flowable:in>` targets, which the guard limits to safe identifiers. Values enter the scope only through `ScriptValues.toJs`, so no Java object reaches the script.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl workflow-engine test -Dtest=SandboxedJavascriptTest`

Expected: PASS. If `[1, 2].map(x => x * 2)` fails, Rhino 1.7.14.1 does not parse arrow functions at `VERSION_ES6`. In that case, replace that assertion with `[1, 2].map(function (x) { return x * 2; })` and add one line to the spec's ES6 note listing arrow functions as unsupported.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/script workflow-agent-service/workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/script
git commit -m "feat: evaluate JavaScript in a safe Rhino scope"
```

---

### Task 3: Sandboxed script task behavior in the engine

**Files:**
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/script/WorkflowScriptTaskActivityBehavior.java`
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/script/WorkflowScriptTaskParseHandler.java`
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/script/WorkflowScriptConfigurator.java`
- Test: `workflow-agent-service/workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/script/WorkflowScriptTaskEngineTest.java`

**Interfaces:**
- Consumes: `SandboxedJavascript.evaluate`, `ScriptTaskProperties`
- Produces: `new WorkflowScriptConfigurator(ScriptTaskProperties)`, an engine configurator that installs the limits and the parse handler.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl workflow-engine test -Dtest=WorkflowScriptTaskEngineTest`

Expected: FAIL because `WorkflowScriptConfigurator` does not exist.

- [ ] **Step 3: Write the minimal implementation**

`WorkflowScriptTaskActivityBehavior.java`:

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.script;

import java.util.LinkedHashMap;
import java.util.Map;
import org.flowable.bpmn.model.ScriptTask;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.bpmn.behavior.ScriptTaskActivityBehavior;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.engine.impl.util.IOParameterUtil;

/**
 * Replaces Flowable's script behavior for every script task. Keeps skip expressions and leaving from the
 * parent; only the evaluation changes: declared inputs only, no execution object, Rhino sandbox.
 */
public class WorkflowScriptTaskActivityBehavior extends ScriptTaskActivityBehavior {

    private static final long serialVersionUID = 1L;
    static final String JAVASCRIPT = "javascript";

    public WorkflowScriptTaskActivityBehavior(ScriptTask scriptTask) {
        super(scriptTask.getId(), scriptTask.getScript(), scriptTask.getScriptFormat(),
                scriptTask.getResultVariable(), scriptTask.getSkipExpression(), false);
    }

    @Override
    protected void executeScript(DelegateExecution execution) {
        if (!JAVASCRIPT.equals(language)) {
            throw new ScriptTaskException(scriptTaskId, "only scriptFormat javascript is supported");
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (execution.getCurrentFlowElement() instanceof ScriptTask scriptTask
                && scriptTask.getInParameters() != null && !scriptTask.getInParameters().isEmpty()) {
            IOParameterUtil.processInParameters(scriptTask.getInParameters(), execution, inputs::put,
                    CommandContextUtil.getProcessEngineConfiguration().getExpressionManager());
        }
        Object result = SandboxedJavascript.evaluate(scriptTaskId, script, inputs);
        if (resultVariable != null && !resultVariable.isBlank()) {
            execution.setVariable(resultVariable, result);
        }
    }
}
```

`WorkflowScriptTaskParseHandler.java`:

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.script;

import org.flowable.bpmn.model.ScriptTask;
import org.flowable.engine.impl.bpmn.parser.BpmnParse;
import org.flowable.engine.impl.bpmn.parser.handler.ScriptTaskParseHandler;

/** Gives every script task the sandboxed behavior, whatever its scriptFormat. */
public class WorkflowScriptTaskParseHandler extends ScriptTaskParseHandler {

    @Override
    protected void executeParse(BpmnParse bpmnParse, ScriptTask scriptTask) {
        scriptTask.setBehavior(new WorkflowScriptTaskActivityBehavior(scriptTask));
    }
}
```

`WorkflowScriptConfigurator.java`:

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable.script;

import io.github.illuseahashmap.workflow.config.ScriptTaskProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.flowable.common.engine.impl.AbstractEngineConfiguration;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.parse.BpmnParseHandler;
import org.flowable.scripting.secure.SecureJavascriptConfigurator;

/**
 * Uses Flowable's secure Rhino context factory (limits and class shutter) but registers our own parse
 * handler, because SecureJavascriptTaskActivityBehavior ignores flowable:in and exposes execution.
 */
public class WorkflowScriptConfigurator extends SecureJavascriptConfigurator {

    public WorkflowScriptConfigurator(ScriptTaskProperties properties) {
        setEnableClassWhiteListing(true);
        setWhiteListedClasses(new HashSet<>());
        setEnableAccessToBeans(false);
        setScriptOptimizationLevel(-1);
        setMaxScriptExecutionTime(properties.getMaxExecutionMillis());
        setMaxMemoryUsed(properties.getMaxMemoryBytes());
        setMaxStackDepth(properties.getMaxStackDepth());
        setNrOfInstructionsBeforeStateCheckCallback(properties.getInstructionCheckInterval());
    }

    @Override
    public void beforeInit(AbstractEngineConfiguration engineConfiguration) {
        if (secureScriptContextFactory == null) {
            initSecureScriptContextFactory();
        }
        ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) engineConfiguration;
        List<BpmnParseHandler> handlers = configuration.getCustomDefaultBpmnParseHandlers();
        if (handlers == null) {
            handlers = new ArrayList<>();
            configuration.setCustomDefaultBpmnParseHandlers(handlers);
        }
        handlers.add(new WorkflowScriptTaskParseHandler());
    }
}
```

`getCustomDefaultBpmnParseHandlers` replaces the default handler for the same element type, so Flowable's own `ScriptTaskParseHandler` no longer runs.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl workflow-engine test -Dtest=WorkflowScriptTaskEngineTest+SandboxedJavascriptTest`

Expected: PASS. If `stopsEndlessLoopsAndRecursion` fails with a raw `java.lang.Error` rather than `ScriptTaskException`, the Rhino interpreter wrapped the limit error. Add a `catch (RuntimeException exception)` branch, after the `IllegalArgumentException` branch in `SandboxedJavascript`, that walks `getCause()` and rethrows the limit as `ScriptTaskException`. Do not widen anything else.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/workflow-engine/src
git commit -m "feat: run every script task through the sandboxed behavior"
```

---

### Task 4: Install the configurator in the application engine

**Files:**
- Modify: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/config/FlowableEngineConfig.java`

**Interfaces:**
- Consumes: `WorkflowScriptConfigurator`, `ScriptTaskProperties`

- [ ] **Step 1: Wire the configurator**

```java
package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.workflow.process.infrastructure.flowable.script.WorkflowScriptConfigurator;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlowableEngineConfig {

    @Bean
    public ProcessEngineConfigurationConfigurer processEngineConfigurationConfigurer(
            ScriptTaskProperties scriptTaskProperties) {
        return configuration -> {
            configuration.setCreateDiagramOnDeploy(false);
            // Always installed: a deployed script task must never fall back to an unsandboxed engine.
            configuration.addConfigurator(new WorkflowScriptConfigurator(scriptTaskProperties));
        };
    }
}
```

- [ ] **Step 2: Run the module and boot tests**

Run: `mvn -pl workflow-engine,workflow-boot -am test`

Expected: PASS. The Testcontainers tests skip without Docker. With Docker, `AgentCompletionFlowableTransactionIntegrationTest` still passes, which shows the engine starts with the configurator.

- [ ] **Step 3: Commit**

```bash
git add workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/config/FlowableEngineConfig.java
git commit -m "feat: install the script sandbox in the process engine"
```

---

### Task 5: Deploy guard

**Files:**
- Create: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ScriptTaskDeploymentGuard.java`
- Test: `workflow-agent-service/workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ScriptTaskDeploymentGuardTest.java`

**Interfaces:**
- Consumes: `ScriptTaskProperties`, `ProcessVariablePolicy.isInternalVariable`
- Produces: `ScriptTaskDeploymentGuard.check(String bpmnXml)` returns normally or throws `BusinessException(BAD_REQUEST)`.

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.config.ScriptTaskProperties;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

    static Stream<String> unsafeDocuments() {
        return Stream.of(
                bpmn(VALID_TASK.replace("javascript", "groovy")),
                bpmn(task("", "", "   ")),
                bpmn(task("", "", "x".repeat(51))),
                bpmn(task("flowable:autoStoreVariables=\"true\"", "", "1")),
                bpmn(task("activiti:autoStoreVariables=\"true\"", "", "1")),
                bpmn(task("flowable:resultVariable=\"1bad\"", "", "1")),
                bpmn(task("flowable:resultVariable=\"tenantId\"", "", "1")),
                bpmn(task("flowable:resultVariableName=\"a_assignee\"", "", "1")),
                bpmn(task("", """
                        <flowable:in source="a" target="a"/>
                        <flowable:in source="b" target="b"/>
                        <flowable:in source="c" target="c"/>""", "1")),
                bpmn(task("", "<flowable:in source=\"a\" target=\"a-b\"/>", "1")),
                bpmn(task("", "<flowable:in source=\"a\" target=\"x\"/><flowable:in source=\"b\" target=\"x\"/>", "1")),
                bpmn(task("", "<flowable:in source=\"a\" sourceExpression=\"${a}\" target=\"x\"/>", "1")),
                bpmn(task("", "<flowable:in target=\"x\"/>", "1")),
                bpmn(task("", "<flowable:in source=\"a.b\" target=\"x\"/>", "1")),
                bpmn(task("", "<flowable:in sourceExpression=\"order.qty\" target=\"x\"/>", "1")),
                bpmn(task("", "<flowable:out source=\"x\" target=\"y\"/>", "1")),
                bpmn("""
                        <bpmn:userTask id="u"><bpmn:extensionElements>
                          <flowable:executionListener event="start" type="script"/>
                        </bpmn:extensionElements></bpmn:userTask>"""),
                bpmn("""
                        <bpmn:userTask id="u"><bpmn:extensionElements>
                          <flowable:taskListener event="create">
                            <flowable:script>1</flowable:script>
                          </flowable:taskListener>
                        </bpmn:extensionElements></bpmn:userTask>"""),
                bpmn("""
                        <bpmn:userTask id="u"><bpmn:extensionElements>
                          <flowable:executionListener event="start"
                              class="org.flowable.engine.impl.bpmn.listener.ScriptExecutionListener"/>
                        </bpmn:extensionElements></bpmn:userTask>"""),
                bpmn("""
                        <bpmn:userTask id="u"><bpmn:extensionElements>
                          <flowable:taskListener event="create"
                              class="org.flowable.scripting.secure.listener.SecureJavascriptTaskListener"/>
                        </bpmn:extensionElements></bpmn:userTask>"""),
                bpmn("""
                        <bpmn:sequenceFlow id="f" sourceRef="a" targetRef="b">
                          <bpmn:conditionExpression language="groovy">x</bpmn:conditionExpression>
                        </bpmn:sequenceFlow>"""),
                bpmn("<bpmn:serviceTask id=\"x\" scriptFormat=\"javascript\"/>"),
                "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e \"e\">]>" + bpmn(VALID_TASK).substring(39));
    }

    @ParameterizedTest
    @MethodSource("unsafeDocuments")
    void rejectsUnsafeDocuments(String xml) {
        assertThatThrownBy(() -> guard(true).check(xml)).isInstanceOf(BusinessException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl workflow-engine test -Dtest=ScriptTaskDeploymentGuardTest`

Expected: FAIL because `ScriptTaskDeploymentGuard` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```java
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
    private static final Set<String> FLOWABLE_NAMESPACES = Set.of(
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

    private void checkAttributes(Element element) {
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
            boolean hasSource = !input.getAttribute("source").isBlank();
            String expression = input.getAttribute("sourceExpression");
            boolean hasExpression = !expression.isBlank();
            if (hasSource == hasExpression) {
                throw invalid("Script input needs exactly one of source or sourceExpression: " + target);
            }
            if (hasSource && !VARIABLE.matcher(input.getAttribute("source")).matches()) {
                throw invalid("Script input source is not a variable name: " + target);
            }
            if (hasExpression && !(expression.contains("${") || expression.contains("#{"))) {
                throw invalid("Script input expression must contain ${ or #{: " + target);
            }
        }
    }

    private void checkListener(Element listener) {
        if ("script".equalsIgnoreCase(listener.getAttribute("type"))) {
            throw invalid("Script listeners are not supported");
        }
        String className = listener.getAttribute("class").trim();
        if (SCRIPT_LISTENER_CLASS.matcher(className).matches()) {
            throw invalid("Script listeners are not supported: " + className);
        }
    }

    private boolean isScriptTaskBody(Element element) {
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
            if (element.hasAttributeNS(namespace, name)) {
                String value = element.getAttributeNS(namespace, name).trim();
                if (!value.isEmpty()) {
                    return value;
                }
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl workflow-engine test -Dtest=ScriptTaskDeploymentGuardTest`

Expected: PASS. The DOCTYPE case builds its XML by cutting the 39-character XML declaration from `bpmn(...)`. If that offset is wrong, the parse still fails, so the assertion still holds. Check that this case fails because of the DOCTYPE and not for some other reason by printing the message once.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ScriptTaskDeploymentGuard.java workflow-agent-service/workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/ScriptTaskDeploymentGuardTest.java
git commit -m "feat: reject unsafe script content at deployment"
```

---

### Task 6: Run the guard before every deployment

**Files:**
- Modify: `workflow-agent-service/workflow-engine/src/main/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/WorkflowDefinitionServiceImpl.java`
- Test: `workflow-agent-service/workflow-engine/src/test/java/io/github/illuseahashmap/workflow/process/infrastructure/flowable/WorkflowDefinitionServiceImplTest.java`

**Interfaces:**
- Consumes: `ScriptTaskDeploymentGuard.check`

- [ ] **Step 1: Write the failing test**

```java
package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.illuseahashmap.workflow.config.ScriptTaskProperties;
import io.github.illuseahashmap.workflow.process.application.AgentBindingDeploymentValidator;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessRequest;
import io.github.illuseahashmap.workflow.process.application.port.AgentTaskBindingParser;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkflowDefinitionServiceImplTest {

    @Test
    void rejectsScriptTasksBeforeTouchingTheEngine() {
        RepositoryService repositoryService = mock(RepositoryService.class);
        TenantProvider tenantProvider = mock(TenantProvider.class);
        AgentTaskBindingParser agentTaskBindingParser = mock(AgentTaskBindingParser.class);
        AgentBindingDeploymentValidator agentValidator = mock(AgentBindingDeploymentValidator.class);
        WorkflowDefinitionServiceImpl service = new WorkflowDefinitionServiceImpl(
                repositoryService, mock(JdbcTemplate.class), tenantProvider, mock(CurrentPrincipalProvider.class),
                agentTaskBindingParser, agentValidator, mock(WorkflowOperationAuditService.class),
                new ScriptTaskDeploymentGuard(new ScriptTaskProperties()));
        String xml = ScriptTaskDeploymentGuardTest.bpmn(ScriptTaskDeploymentGuardTest.VALID_TASK);

        assertThatThrownBy(() -> service.deploy(new DeployProcessRequest("p", "P", xml)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("disabled");
        verifyNoInteractions(repositoryService, tenantProvider, agentTaskBindingParser, agentValidator);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl workflow-engine test -Dtest=WorkflowDefinitionServiceImplTest`

Expected: FAIL to compile, because the constructor has no guard parameter.

- [ ] **Step 3: Write the minimal implementation**

In `WorkflowDefinitionServiceImpl`:
- Add the field `private final ScriptTaskDeploymentGuard scriptTaskDeploymentGuard;`.
- Add the constructor parameter `ScriptTaskDeploymentGuard scriptTaskDeploymentGuard` last, and assign it.
- Make `check` the first line of `deploy`, before `tenantProvider.current()`:

```java
    public DeployProcessResult deploy(DeployProcessRequest request) {
        scriptTaskDeploymentGuard.check(request.bpmnXml());
        TenantContext.TenantInfo tenant = tenantProvider.current();
```

The guard is in the same package, so no import is needed.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl workflow-engine test`

Expected: PASS for the whole module.

- [ ] **Step 5: Commit**

```bash
git add workflow-agent-service/workflow-engine/src
git commit -m "feat: guard deployments against unsafe scripts"
```

---

### Task 7: Moddle types and panel rules on the web

**Files:**
- Modify: `workflow-agent-web/package.json` (devDependency `bpmn-moddle`)
- Modify: `workflow-agent-web/src/bpmn/flowableModdle.ts`
- Create: `workflow-agent-web/src/features/process-definition/scriptTaskPanel.ts`
- Modify: `workflow-agent-web/src/features/process-definition/serviceTaskPanel.ts`
- Test: `workflow-agent-web/src/features/process-definition/scriptTaskPanel.spec.ts`
- Modify: `workflow-agent-web/src/features/process-definition/serviceTaskPanel.spec.ts`

**Interfaces:**
- Produces: `ScriptInputRow { target: string; source: string }`, `toInParameters(rows)`, `fromInParameters(values)`, `scriptTaskWarnings(script, rows, resultVariable)`, `isAutomaticTask(type)`.

- [ ] **Step 1: Add the test dependency**

Run: `pnpm add -D bpmn-moddle@10.1.0`

This is the version bpmn-js already resolves. If `vue-tsc` later reports missing types for `bpmn-moddle`, add `declare module 'bpmn-moddle'` to `src/env.d.ts`.

- [ ] **Step 2: Write the failing test**

`scriptTaskPanel.spec.ts`:

```typescript
import BpmnModdle from 'bpmn-moddle'
import { describe, expect, it } from 'vitest'
import flowableModdle from '@/bpmn/flowableModdle'
import {
  fromInParameters,
  isAutomaticTask,
  scriptTaskWarnings,
  toInParameters,
} from './scriptTaskPanel'

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="test">
  <bpmn:process id="p">
    <bpmn:scriptTask id="s" scriptFormat="javascript" flowable:resultVariable="total">
      <bpmn:extensionElements>
        <flowable:in source="amount" target="amount" />
        <flowable:in sourceExpression="\${order.qty}" target="qty" />
      </bpmn:extensionElements>
      <bpmn:script>amount * qty</bpmn:script>
    </bpmn:scriptTask>
  </bpmn:process>
</bpmn:definitions>`

describe('script task panel', () => {
  it('maps rows to flowable:in and back', () => {
    const parameters = toInParameters([
      { target: ' amount ', source: 'amount' },
      { target: 'qty', source: '${order.qty}' },
      { target: '', source: '' },
    ])
    expect(parameters).toEqual([
      { target: 'amount', source: 'amount' },
      { target: 'qty', sourceExpression: '${order.qty}' },
    ])
    expect(fromInParameters(parameters)).toEqual([
      { target: 'amount', source: 'amount' },
      { target: 'qty', source: '${order.qty}' },
    ])
  })

  it('warns about rows the backend will reject', () => {
    expect(scriptTaskWarnings('a', [{ target: 'a', source: 'a' }], 'total')).toEqual([])
    expect(scriptTaskWarnings('  ', [], '')).toEqual(['Script đang trống'])
    expect(
      scriptTaskWarnings(
        'a',
        [
          { target: 'a-b', source: 'a' },
          { target: 'x', source: 'a' },
          { target: 'x', source: 'b.c' },
        ],
        '1bad',
      ),
    ).toEqual([
      'Tên đầu vào không hợp lệ: a-b',
      'Tên đầu vào bị trùng: x',
      'Nguồn không hợp lệ cho x',
      'Tên biến kết quả không hợp lệ: 1bad',
    ])
  })

  it('treats script and service tasks as automatic', () => {
    expect(isAutomaticTask('bpmn:ScriptTask')).toBe(true)
    expect(isAutomaticTask('bpmn:ServiceTask')).toBe(true)
    expect(isAutomaticTask('bpmn:UserTask')).toBe(false)
  })

  it('keeps Flowable script attributes through moddle', async () => {
    const moddle = new BpmnModdle({ flowable: flowableModdle })
    const { rootElement } = await moddle.fromXML(XML)
    const task = rootElement.rootElements[0].flowElements[0]

    expect(task.resultVariable).toBe('total')
    expect(task.$attrs['flowable:resultVariable']).toBeUndefined()
    expect(task.extensionElements.values.map((value: { $type: string }) => value.$type)).toEqual([
      'flowable:In',
      'flowable:In',
    ])

    const { xml } = await moddle.toXML(rootElement)
    expect(xml).toContain('flowable:resultVariable="total"')
    expect(xml).toContain('<flowable:in source="amount" target="amount" />')
    expect(xml).toContain('sourceExpression="${order.qty}"')
  })
})
```

Add to `serviceTaskPanel.spec.ts`, inside the existing `describe`:

```typescript
  it('hides approval mode on a script task', () => {
    expect(showsApprovalMode(true, isAutomaticTask('bpmn:ScriptTask'))).toBe(false)
  })
```

Also add `import { isAutomaticTask } from './scriptTaskPanel'`.

- [ ] **Step 3: Run test to verify it fails**

Run: `pnpm test:unit:run src/features/process-definition`

Expected: FAIL because `scriptTaskPanel` does not exist.

- [ ] **Step 4: Write the minimal implementation**

Append to `types` in `flowableModdle.ts`:

```typescript
    {
      name: 'ScriptTask',
      extends: ['bpmn:ScriptTask'],
      properties: [
        { name: 'resultVariable', isAttr: true, type: 'String' },
        { name: 'autoStoreVariables', isAttr: true, type: 'Boolean' },
      ],
    },
    {
      name: 'In',
      superClass: ['Element'],
      properties: [
        { name: 'source', isAttr: true, type: 'String' },
        { name: 'sourceExpression', isAttr: true, type: 'String' },
        { name: 'target', isAttr: true, type: 'String' },
      ],
    },
```

`scriptTaskPanel.ts`:

```typescript
export interface ScriptInputRow {
  target: string
  source: string
}

export interface FlowableInParameter {
  target: string
  source?: string
  sourceExpression?: string
}

const VARIABLE = /^[A-Za-z][A-Za-z0-9_]{0,127}$/
const TARGET = /^[A-Za-z_$][A-Za-z0-9_$]{0,127}$/

export function isExpression(value: string) {
  return value.includes('${') || value.includes('#{')
}

export function toInParameters(rows: ScriptInputRow[]): FlowableInParameter[] {
  return rows
    .map((row) => ({ target: row.target.trim(), source: row.source.trim() }))
    .filter((row) => row.target || row.source)
    .map((row) =>
      isExpression(row.source)
        ? { target: row.target, sourceExpression: row.source }
        : { target: row.target, source: row.source },
    )
}

export function fromInParameters(
  values: Array<{ target?: unknown; source?: unknown; sourceExpression?: unknown }>,
): ScriptInputRow[] {
  return values.map((value) => ({
    target: String(value.target || ''),
    source: String(value.sourceExpression || value.source || ''),
  }))
}

export function scriptTaskWarnings(
  script: string,
  rows: ScriptInputRow[],
  resultVariable: string,
): string[] {
  const warnings: string[] = []
  if (!script.trim()) warnings.push('Script đang trống')
  const seen = new Set<string>()
  for (const row of toInParameters(rows)) {
    if (!TARGET.test(row.target)) {
      warnings.push(`Tên đầu vào không hợp lệ: ${row.target || '(trống)'}`)
    } else if (seen.has(row.target)) {
      warnings.push(`Tên đầu vào bị trùng: ${row.target}`)
    }
    seen.add(row.target)
    if (!row.sourceExpression && !VARIABLE.test(row.source || '')) {
      warnings.push(`Nguồn không hợp lệ cho ${row.target}`)
    }
  }
  const result = resultVariable.trim()
  if (result && !VARIABLE.test(result)) warnings.push(`Tên biến kết quả không hợp lệ: ${result}`)
  return warnings
}

export function isAutomaticTask(type: string) {
  return type === 'bpmn:ServiceTask' || type === 'bpmn:ScriptTask'
}
```

`serviceTaskPanel.ts` keeps its signature. Rename the second parameter to make clear it now covers both automatic kinds:

```typescript
export function showsApprovalMode(isTask: boolean, isAutomaticTask: boolean) {
  return isTask && !isAutomaticTask
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `pnpm test:unit:run src/features/process-definition`

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add workflow-agent-web/package.json workflow-agent-web/pnpm-lock.yaml workflow-agent-web/src/bpmn/flowableModdle.ts workflow-agent-web/src/features/process-definition
git commit -m "feat: model Flowable script task attributes in the designer"
```

---

### Task 8: Palette entry, panel and designer wiring

**Files:**
- Modify: `workflow-agent-web/src/config/product.ts`
- Modify: `workflow-agent-web/.env.example`
- Modify: `workflow-agent-web/src/bpmn/workflowPaletteModule.ts`
- Create: `workflow-agent-web/src/features/process-definition/components/ScriptTaskConfigPanel.vue`
- Modify: `workflow-agent-web/src/features/process-definition/views/ProcessDesignerView.vue`

**Interfaces:**
- Consumes: `scriptTaskPanel.ts` from Task 7.

- [ ] **Step 1: Feature switch**

Append to `product.ts`:

```typescript
/** Shows the script step in the palette. The backend flag workflow.script-task.enabled still decides. */
export const scriptTasksEnabled = import.meta.env.VITE_SCRIPT_TASKS_ENABLED === 'true'
```

Append `VITE_SCRIPT_TASKS_ENABLED=false` to `workflow-agent-web/.env.example`.

- [ ] **Step 2: Palette entry**

In `workflowPaletteModule.ts`:
- Import `scriptTasksEnabled` with `agentsEnabled`.
- Change `ElementFactory.createShape` to accept `{ type: string; businessObject?: unknown }`.
- Add `interface BpmnFactory { create(type: string, attrs?: Record<string, unknown>): unknown }`.
- Inject it: `static $inject = ['palette', 'create', 'elementFactory', 'bpmnFactory']`, and add the constructor parameter `private readonly bpmnFactory: BpmnFactory`.

Inside `getPaletteEntries`, before `if (!agentsEnabled)`:

```typescript
      const createScriptTask = (event: Event) => {
        const businessObject = this.bpmnFactory.create('bpmn:ScriptTask', {
          scriptFormat: 'javascript',
        })
        const shape = this.elementFactory.createShape({ type: 'bpmn:ScriptTask', businessObject })
        this.create.start(event, shape)
      }

      if (scriptTasksEnabled) {
        entries['create.script-task'] = {
          group: 'activity',
          className: 'bpmn-icon-script-task',
          title: 'Thêm bước script',
          action: { dragstart: createScriptTask, click: createScriptTask },
        }
      }
```

- [ ] **Step 3: Panel component**

`ScriptTaskConfigPanel.vue`:

```vue
<script setup lang="ts">
import type { ScriptInputRow } from '../scriptTaskPanel'

const props = defineProps<{
  script: string
  inputRows: ScriptInputRow[]
  resultVariable: string
}>()

const emit = defineEmits<{
  'update:script': [value: string]
  'update:inputRows': [value: ScriptInputRow[]]
  'update:resultVariable': [value: string]
  apply: []
}>()

function updateRow(index: number, patch: Partial<ScriptInputRow>) {
  emit(
    'update:inputRows',
    props.inputRows.map((row, rowIndex) => (rowIndex === index ? { ...row, ...patch } : row)),
  )
}

function addRow() {
  emit('update:inputRows', [...props.inputRows, { target: '', source: '' }])
}

function removeRow(index: number) {
  emit(
    'update:inputRows',
    props.inputRows.filter((_, rowIndex) => rowIndex !== index),
  )
  emit('apply')
}
</script>

<template>
  <div class="property-block">
    <strong>Script</strong>
    <el-form label-position="top" size="small">
      <el-form-item label="JavaScript">
        <el-input
          :model-value="script"
          type="textarea"
          :rows="10"
          class="script-editor"
          placeholder="amount * qty"
          @update:model-value="emit('update:script', $event)"
          @blur="emit('apply')"
        />
        <p class="script-hint">
          Giá trị của dòng cuối là kết quả. Script chỉ thấy các đầu vào bên dưới, không gọi được
          Java, và chạy trên Rhino nên chỉ hỗ trợ một phần ES6.
        </p>
      </el-form-item>
      <el-form-item label="Đầu vào">
        <div v-for="(row, index) in inputRows" :key="index" class="script-input-row">
          <el-input
            :model-value="row.target"
            placeholder="Tên trong script"
            @update:model-value="updateRow(index, { target: $event })"
            @blur="emit('apply')"
          />
          <el-input
            :model-value="row.source"
            placeholder="Biến, hoặc ${biểu thức}"
            @update:model-value="updateRow(index, { source: $event })"
            @blur="emit('apply')"
          />
          <el-button text @click="removeRow(index)">Xóa</el-button>
        </div>
        <el-button size="small" @click="addRow">Thêm đầu vào</el-button>
      </el-form-item>
      <el-form-item label="Biến kết quả">
        <el-input
          :model-value="resultVariable"
          placeholder="total"
          @update:model-value="emit('update:resultVariable', $event)"
          @blur="emit('apply')"
        />
      </el-form-item>
    </el-form>
  </div>
</template>

<style scoped>
.script-editor :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.script-hint {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.4;
  color: var(--el-text-color-secondary);
}

.script-input-row {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 6px;
  width: 100%;
  margin-bottom: 6px;
}
</style>
```

- [ ] **Step 4: Designer wiring**

In `ProcessDesignerView.vue`:

1. Imports:

```typescript
import ScriptTaskConfigPanel from '../components/ScriptTaskConfigPanel.vue'
import {
  fromInParameters,
  scriptTaskWarnings,
  toInParameters,
  type ScriptInputRow,
} from '../scriptTaskPanel'
```

2. State, next to `serviceOutputRows`:

```typescript
const scriptText = ref('')
const scriptInputRows = ref<ScriptInputRow[]>([])
const scriptResultVariable = ref('')
```

3. Computed values:

```typescript
const isScriptTask = computed(() => selectedType.value === 'bpmn:ScriptTask')
```

Add `'bpmn:ScriptTask'` to the `isTask` list.

4. In `getElementTypeLabel`, add `'bpmn:ScriptTask': 'Chạy script',`.

5. In `resetSelection`, after the service-binding `if/else`:

```typescript
  const isScript = object?.$type === 'bpmn:ScriptTask'
  scriptText.value = isScript ? String(object?.script || '') : ''
  scriptResultVariable.value = isScript ? String(object?.resultVariable || '') : ''
  scriptInputRows.value = isScript
    ? fromInParameters(
        object?.extensionElements?.values?.filter((item) => item.$type === 'flowable:In') || [],
      )
    : []
```

6. Add the apply function after `applyServiceCall`:

```typescript
function applyScriptTask(warn = false) {
  const element = selectedElement.value
  const object = selectedBusinessObject.value
  const modeling = service<Modeling>('modeling')
  const moddle = service<Moddle>('moddle')
  if (!element || !object || object.$type !== 'bpmn:ScriptTask' || !modeling || !moddle) return
  if (warn) {
    const warnings = scriptTaskWarnings(
      scriptText.value,
      scriptInputRows.value,
      scriptResultVariable.value,
    )
    if (warnings.length > 0) ElMessage.warning(warnings.join('; '))
  }
  const kept = (object.extensionElements?.values || []).filter(
    (item) => item.$type !== 'flowable:In',
  )
  const inputs = toInParameters(scriptInputRows.value).map((parameter) =>
    moddle.create('flowable:In', { ...parameter }),
  )
  const values = [...kept, ...inputs]
  modeling.updateModdleProperties(element, object, {
    scriptFormat: 'javascript',
    script: scriptText.value,
    resultVariable: scriptResultVariable.value.trim() || undefined,
    autoStoreVariables: undefined,
    extensionElements: values.length
      ? moddle.create('bpmn:ExtensionElements', { values })
      : undefined,
  })
  dirty.value = true
}
```

7. In `saveVersion`, directly after `applyServiceCall()`, add `applyScriptTask(true)`.

8. In the template, after `<ServiceCallConfigPanel ... />`:

```vue
            <ScriptTaskConfigPanel
              v-if="isScriptTask"
              v-model:script="scriptText"
              v-model:input-rows="scriptInputRows"
              v-model:result-variable="scriptResultVariable"
              @apply="applyScriptTask()"
            />
```

9. Change the «Loại bước» condition to `showsApprovalMode(isTask, isServiceTask || isScriptTask)`.

- [ ] **Step 5: Check the web build**

Run: `pnpm lint && pnpm type-check && pnpm test:unit:run && pnpm build-only`

Expected: all pass.

- [ ] **Step 6: Manual check**

Run the backend with `WORKFLOW_SCRIPT_TASK_ENABLED=true`, and the web app with `VITE_SCRIPT_TASKS_ENABLED=true`. Then:
1. Add «Thêm bước script» between start and end. Enter `amount * 2`, input `amount` ← `amount`, result `total`. Save.
2. Start the process with `amount=21`. The instance variables show `total = 42`.
3. Change the script to `java.lang.System.exit(0)` and save. Starting the process fails with a `Script task ... failed` message, and no instance is created.
4. Restart the backend with the flag `false`. Saving the diagram shows `Script tasks are disabled: ...`.

- [ ] **Step 7: Commit**

```bash
git add workflow-agent-web/src workflow-agent-web/.env.example
git commit -m "feat: add the script step to the process designer"
```

---

### Task 9: Full verification

- [ ] **Step 1: Backend**

Run: `mvn verify` from `workflow-agent-service`

Expected: BUILD SUCCESS, including SpotBugs, JaCoCo thresholds, and the ArchUnit tests. If SpotBugs flags `WorkflowScriptConfigurator` for writing the inherited static `secureScriptContextFactory`, add a narrowly scoped exclusion for that class only. The write happens in Flowable's own code.

- [ ] **Step 2: Web**

Run: `pnpm check` from `workflow-agent-web`

Expected: PASS

- [ ] **Step 3: Rhino advisory check**

Check `org.mozilla:rhino:1.7.14.1` against the GitHub advisory database and OSV. Record the result under «Câu hỏi còn mở» in the spec before anyone sets `WORKFLOW_SCRIPT_TASK_ENABLED=true` in production.

- [ ] **Step 4: Commit any fixes**

```bash
git add -A
git commit -m "chore: verify the script task node end to end"
```
