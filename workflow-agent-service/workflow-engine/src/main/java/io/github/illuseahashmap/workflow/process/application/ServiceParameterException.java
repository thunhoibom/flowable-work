package io.github.illuseahashmap.workflow.process.application;

public class ServiceParameterException extends RuntimeException {

    public ServiceParameterException(String message) {
        super(message);
    }

    public ServiceParameterException(String message, Throwable cause) {
        super(message, cause);
    }
}
