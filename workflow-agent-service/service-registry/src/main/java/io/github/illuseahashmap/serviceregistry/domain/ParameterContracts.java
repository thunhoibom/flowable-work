package io.github.illuseahashmap.serviceregistry.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ParameterContracts {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    private ParameterContracts() {
    }

    public static void check(List<InputParameter> inputs, List<OutputParameter> outputs) {
        checkInputs(inputs == null ? List.of() : inputs);
        checkOutputs(outputs == null ? List.of() : outputs);
    }

    private static void checkInputs(List<InputParameter> inputs) {
        Set<String> names = new HashSet<>();
        for (InputParameter input : inputs) {
            if (input == null || input.type() == null || input.location() == null || !NAME.matcher(text(input.name())).matches()) {
                throw new IllegalArgumentException("Invalid input parameter");
            }
            if (!names.add(input.name())) {
                throw new IllegalArgumentException("Duplicate input parameter");
            }
            if (input.location() == ParameterLocation.PATH && !input.required()) {
                throw new IllegalArgumentException("PATH parameter must be required");
            }
        }
    }

    private static void checkOutputs(List<OutputParameter> outputs) {
        Set<String> names = new HashSet<>();
        for (OutputParameter output : outputs) {
            if (output == null || output.type() == null || !NAME.matcher(text(output.name())).matches()) {
                throw new IllegalArgumentException("Invalid output parameter");
            }
            if (!names.add(output.name())) {
                throw new IllegalArgumentException("Duplicate output parameter");
            }
            String field = output.jsonField();
            if (field == null || field.isBlank() || field.length() > 256 || hasControl(field)) {
                throw new IllegalArgumentException("Invalid JSON field");
            }
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
