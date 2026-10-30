package io.github.illuseahashmap.workflow.process.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

public final class ParameterCoercion {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private ParameterCoercion() {
    }

    public static Object coerce(ServiceParameterType type, Object value) {
        return switch (type) {
            case STRING -> asString(value);
            case INTEGER -> asInteger(value);
            case LONG -> asLong(value);
            case DOUBLE -> asDouble(value);
            case BOOLEAN -> asBoolean(value);
            case JSON -> asJson(value);
        };
    }

    private static String asString(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Boolean flag) {
            return flag ? "true" : "false";
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return Long.toString(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw rejected(ServiceParameterType.STRING);
            }
            if (integralLong(number)) {
                return Long.toString((long) number);
            }
            return Double.toString(number);
        }
        throw rejected(ServiceParameterType.STRING);
    }

    private static int asInteger(Object value) {
        long number = integral(value, ServiceParameterType.INTEGER);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw rejected(ServiceParameterType.INTEGER);
        }
        return (int) number;
    }

    private static long asLong(Object value) {
        return integral(value, ServiceParameterType.LONG);
    }

    private static long integral(Object value, ServiceParameterType type) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!integralLong(number)) {
                throw rejected(type);
            }
            return (long) number;
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException exception) {
                throw rejected(type);
            }
        }
        throw rejected(type);
    }

    private static double asDouble(Object value) {
        double number;
        if (value instanceof Number numeric) {
            number = numeric.doubleValue();
        } else if (value instanceof String text) {
            try {
                number = Double.parseDouble(text.trim());
            } catch (NumberFormatException exception) {
                throw rejected(ServiceParameterType.DOUBLE);
            }
        } else {
            throw rejected(ServiceParameterType.DOUBLE);
        }
        if (!Double.isFinite(number)) {
            throw rejected(ServiceParameterType.DOUBLE);
        }
        return number;
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text && ("true".equals(text) || "false".equals(text))) {
            return Boolean.parseBoolean(text);
        }
        throw rejected(ServiceParameterType.BOOLEAN);
    }

    private static Object asJson(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?> || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            try {
                JsonNode node = JSON_MAPPER.readTree(text);
                if (node == null || node.isNull()) {
                    return null;
                }
                return JSON_MAPPER.convertValue(node, Object.class);
            } catch (JsonProcessingException exception) {
                throw rejected(ServiceParameterType.JSON);
            }
        }
        throw rejected(ServiceParameterType.JSON);
    }

    private static boolean integralLong(double number) {
        if (!Double.isFinite(number) || number != Math.rint(number)) {
            return false;
        }
        return number >= Long.MIN_VALUE && number <= Long.MAX_VALUE;
    }

    private static IllegalArgumentException rejected(ServiceParameterType type) {
        return new IllegalArgumentException("cannot coerce to " + type.name());
    }
}
