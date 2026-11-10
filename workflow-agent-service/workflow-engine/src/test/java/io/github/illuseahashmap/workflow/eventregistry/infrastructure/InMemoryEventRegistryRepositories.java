package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.eventregistry.application.port.EventReferenceRepository;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryDraftRepository;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryReleaseRepository;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventReference;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryDraft;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryModelType;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryRelease;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/** In-memory event registry ports for engine-only tests. */
final class InMemoryEventRegistryRepositories {

    final Drafts drafts = new Drafts();
    final Releases releases = new Releases();
    final References references = new References();

    /** Holds references without process versions: every stored row counts as the latest version 1. */
    static final class References implements EventReferenceRepository {

        final Map<String, List<EventReference>> rows = new TreeMap<>();

        @Override
        public void replaceForDefinition(String tenantId, String processDefinitionId,
                                         List<EventReference> references) {
            rows.put(tenantId + "|" + processDefinitionId, List.copyOf(references));
        }

        @Override
        public void deleteForDefinition(String tenantId, String processDefinitionId) {
            rows.remove(tenantId + "|" + processDefinitionId);
        }

        @Override
        public void deleteForProcessKey(String tenantId, String processDefinitionKey) {
            rows.keySet().removeIf(id -> id.startsWith(tenantId + "|" + processDefinitionKey + ":"));
        }

        @Override
        public long latestProcessDefinitionsUsing(String tenantId, String eventKey) {
            return using(tenantId, eventKey).map(EventReference::processDefinitionId).distinct().count();
        }

        @Override
        public List<EventUsage> usages(String tenantId, String eventKey) {
            return using(tenantId, eventKey)
                    .map(reference -> new EventUsage(reference.processDefinitionId().split(":")[0], 1,
                            reference.activityId()))
                    .sorted(Comparator.comparing(EventUsage::processDefinitionKey)
                            .thenComparing(EventUsage::activityId))
                    .toList();
        }

        private Stream<EventReference> using(String tenantId, String eventKey) {
            return rows.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(tenantId + "|"))
                    .flatMap(entry -> entry.getValue().stream())
                    .filter(reference -> reference.eventKey().equals(eventKey));
        }
    }

    private static String id(String tenantId, RegistryModelType type, String key) {
        return tenantId + "|" + type + "|" + key;
    }

    static final class Drafts implements EventRegistryDraftRepository {

        private final Map<String, RegistryDraft> rows = new TreeMap<>();

        @Override
        public Optional<RegistryDraft> find(String tenantId, RegistryModelType type, String key) {
            return Optional.ofNullable(rows.get(id(tenantId, type, key)));
        }

        @Override
        public Optional<RegistryDraft> findForUpdate(String tenantId, RegistryModelType type, String key) {
            return find(tenantId, type, key);
        }

        @Override
        public boolean insert(RegistryDraft draft) {
            return rows.putIfAbsent(id(draft.tenantId(), draft.type(), draft.key()), draft) == null;
        }

        @Override
        public boolean update(RegistryDraft draft, long expectedRevision) {
            String id = id(draft.tenantId(), draft.type(), draft.key());
            RegistryDraft current = rows.get(id);
            if (current == null || current.revision() != expectedRevision) {
                return false;
            }
            rows.put(id, draft);
            return true;
        }

        @Override
        public List<RegistryDraft> list(String tenantId, RegistryModelType type) {
            return rows.values().stream()
                    .filter(draft -> draft.tenantId().equals(tenantId) && draft.type() == type)
                    .sorted(Comparator.comparing(RegistryDraft::key))
                    .toList();
        }

        @Override
        public void delete(String tenantId, RegistryModelType type, String key) {
            rows.remove(id(tenantId, type, key));
        }
    }

    static final class Releases implements EventRegistryReleaseRepository {

        final List<RegistryRelease> rows = new ArrayList<>();

        @Override
        public void record(RegistryRelease release) {
            rows.add(release);
        }

        @Override
        public Optional<RegistryRelease> latest(String tenantId, RegistryModelType type, String key) {
            return rows.reversed().stream()
                    .filter(release -> release.tenantId().equals(tenantId) && release.type() == type
                            && release.key().equals(key))
                    .findFirst();
        }
    }
}
