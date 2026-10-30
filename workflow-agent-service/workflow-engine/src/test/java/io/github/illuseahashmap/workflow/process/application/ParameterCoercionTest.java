package io.github.illuseahashmap.workflow.process.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParameterCoercionTest {

    @Test
    void coercesScalarsAndRejectsMismatches() {
        assertEquals("Ann", ParameterCoercion.coerce(ServiceParameterType.STRING, "Ann"));
        assertEquals("1", ParameterCoercion.coerce(ServiceParameterType.STRING, 1.0d));
        assertEquals("1.5", ParameterCoercion.coerce(ServiceParameterType.STRING, 1.5d));
        assertEquals("true", ParameterCoercion.coerce(ServiceParameterType.STRING, true));
        assertEquals(1, ParameterCoercion.coerce(ServiceParameterType.INTEGER, 1.0d));
        assertEquals(1, ParameterCoercion.coerce(ServiceParameterType.INTEGER, "1"));
        assertEquals(4L, ParameterCoercion.coerce(ServiceParameterType.LONG, "4"));
        assertEquals(1.5d, ParameterCoercion.coerce(ServiceParameterType.DOUBLE, "1.5"));
        assertEquals(true, ParameterCoercion.coerce(ServiceParameterType.BOOLEAN, "true"));
        assertEquals(false, ParameterCoercion.coerce(ServiceParameterType.BOOLEAN, false));
        assertEquals(List.of(1), ParameterCoercion.coerce(ServiceParameterType.JSON, "[1]"));
        assertNull(ParameterCoercion.coerce(ServiceParameterType.JSON, "null"));

        assertThrows(IllegalArgumentException.class,
                () -> ParameterCoercion.coerce(ServiceParameterType.INTEGER, "1.0"));
        assertThrows(IllegalArgumentException.class,
                () -> ParameterCoercion.coerce(ServiceParameterType.INTEGER, 1.5d));
        assertThrows(IllegalArgumentException.class,
                () -> ParameterCoercion.coerce(ServiceParameterType.BOOLEAN, "TRUE"));
        assertThrows(IllegalArgumentException.class,
                () -> ParameterCoercion.coerce(ServiceParameterType.STRING, Map.of("a", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> ParameterCoercion.coerce(ServiceParameterType.DOUBLE, Double.NaN));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ParameterCoercion.coerce(ServiceParameterType.INTEGER, "secret-value"));
        org.junit.jupiter.api.Assertions.assertFalse(failure.getMessage().contains("secret-value"));
    }
}
