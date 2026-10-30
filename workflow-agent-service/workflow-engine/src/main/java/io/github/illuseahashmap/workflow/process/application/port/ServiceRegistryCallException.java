package io.github.illuseahashmap.workflow.process.application.port;

public class ServiceRegistryCallException extends RuntimeException {

    private final String bpmnErrorCode;

    public ServiceRegistryCallException(String bpmnErrorCode) {
        super(bpmnErrorCode);
        this.bpmnErrorCode = bpmnErrorCode;
    }

    public String bpmnErrorCode() {
        return bpmnErrorCode;
    }
}
