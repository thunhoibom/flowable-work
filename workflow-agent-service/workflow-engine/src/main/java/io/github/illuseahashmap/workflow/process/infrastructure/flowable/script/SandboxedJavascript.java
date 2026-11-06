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
