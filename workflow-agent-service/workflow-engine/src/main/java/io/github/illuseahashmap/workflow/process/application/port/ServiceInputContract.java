package io.github.illuseahashmap.workflow.process.application.port;

import io.github.illuseahashmap.workflow.process.application.ServiceParameterType;

public record ServiceInputContract(String name, ServiceParameterType type, boolean required) {
}
