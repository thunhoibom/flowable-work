package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

public record ServiceDraft(String serviceKey, String name, List<PublishedOperation> operations) {
}
