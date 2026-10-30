package io.github.illuseahashmap.serviceregistry.domain;

public class InvalidServiceRequestException extends RuntimeException {

    public InvalidServiceRequestException(String message) {
        super(message);
    }
}
