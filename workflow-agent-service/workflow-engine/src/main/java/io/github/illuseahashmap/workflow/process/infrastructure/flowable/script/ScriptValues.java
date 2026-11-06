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
                ScriptableObject.putProperty(object, key,
                        toJs(cx, scope, path + "." + key, entry.getValue(), depth + 1));
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
