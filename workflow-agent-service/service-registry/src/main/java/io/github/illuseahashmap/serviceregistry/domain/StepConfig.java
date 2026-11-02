package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

/**
 * Pagination and webhook wait for one operation. Missing values stay a single synchronous call
 * so operations saved before this slice do not change.
 */
public record StepConfig(
        PaginationMode pagination,
        String nextField,
        String nextTokenParameter,
        int maxPages,
        CallStyle callStyle,
        String correlationOutput,
        int waitTimeoutMillis) {

    public static final int DEFAULT_MAX_PAGES = 10;
    public static final int MAX_PAGES_LIMIT = 100;

    public StepConfig {
        if (pagination == null) {
            pagination = PaginationMode.NONE;
        }
        if (callStyle == null) {
            callStyle = CallStyle.SYNC;
        }
        if (maxPages <= 0) {
            maxPages = DEFAULT_MAX_PAGES;
        }
        nextField = blankToNull(nextField);
        nextTokenParameter = blankToNull(nextTokenParameter);
        correlationOutput = blankToNull(correlationOutput);
    }

    public static StepConfig sync() {
        return new StepConfig(PaginationMode.NONE, null, null, DEFAULT_MAX_PAGES, CallStyle.SYNC, null, 0);
    }

    /** Rejects a page loop or a callback that cannot be matched. */
    public void check(List<InputParameter> inputs, List<OutputParameter> outputs) {
        if (maxPages > MAX_PAGES_LIMIT) {
            throw new IllegalArgumentException("Max pages must be from 1 to 100");
        }
        if (pagination != PaginationMode.NONE && nextField == null) {
            throw new IllegalArgumentException("Next field is required");
        }
        if (pagination == PaginationMode.NEXT_TOKEN) {
            if (nextTokenParameter == null) {
                throw new IllegalArgumentException("Next token parameter is required");
            }
            boolean known = inputs != null && inputs.stream()
                    .anyMatch(input -> input != null && nextTokenParameter.equals(input.name()));
            if (!known) {
                throw new IllegalArgumentException("Next token parameter is not an input");
            }
        }
        if (callStyle != CallStyle.CALLBACK) {
            return;
        }
        if (correlationOutput == null) {
            throw new IllegalArgumentException("Correlation output is required");
        }
        boolean known = outputs != null && outputs.stream()
                .anyMatch(output -> output != null && correlationOutput.equals(output.name()));
        if (!known) {
            throw new IllegalArgumentException("Correlation output is not an output");
        }
        if (waitTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Wait timeout is required");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
