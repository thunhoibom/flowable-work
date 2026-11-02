package io.github.illuseahashmap.serviceregistry.application;

/** Name of the environment whose base URL is used for relative operation paths. */
@FunctionalInterface
public interface ServiceEnvironment {

    String name();
}
