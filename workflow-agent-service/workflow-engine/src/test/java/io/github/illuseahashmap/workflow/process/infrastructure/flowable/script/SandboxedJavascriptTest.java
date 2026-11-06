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
