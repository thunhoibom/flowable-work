package io.github.illuseahashmap.workflow.process.application.port;

import java.util.List;

public record PublishedCallContract(
        List<ServiceInputContract> inputs,
        List<ServiceOutputContract> outputs) {
}
