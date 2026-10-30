package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

public record ServiceView(String serviceKey, String name, List<OperationView> operations) {
}
