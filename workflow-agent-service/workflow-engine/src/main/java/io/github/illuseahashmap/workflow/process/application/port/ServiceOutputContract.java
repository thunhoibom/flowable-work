package io.github.illuseahashmap.workflow.process.application.port;

import io.github.illuseahashmap.workflow.process.application.ServiceParameterType;

public record ServiceOutputContract(String name, ServiceParameterType type) {
}
