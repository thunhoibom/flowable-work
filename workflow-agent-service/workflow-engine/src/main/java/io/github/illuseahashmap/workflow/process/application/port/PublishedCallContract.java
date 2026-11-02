package io.github.illuseahashmap.workflow.process.application.port;

import java.util.List;

public record PublishedCallContract(
        List<ServiceInputContract> inputs,
        List<ServiceOutputContract> outputs,
        String bearerVariable,
        String callStyle,
        String correlationOutput,
        int waitTimeoutMillis) {

    public PublishedCallContract(List<ServiceInputContract> inputs, List<ServiceOutputContract> outputs) {
        this(inputs, outputs, null);
    }

    public PublishedCallContract(
            List<ServiceInputContract> inputs, List<ServiceOutputContract> outputs, String bearerVariable) {
        this(inputs, outputs, bearerVariable, "SYNC", null, 0);
    }
}
