package io.github.illuseahashmap.serviceregistry.application;

public class ServiceCallException extends RuntimeException {

    private final String bpmnErrorCode;

    public ServiceCallException(String bpmnErrorCode) {
        super(bpmnErrorCode);
        this.bpmnErrorCode = bpmnErrorCode;
    }

    public String bpmnErrorCode() {
        return bpmnErrorCode;
    }
}
