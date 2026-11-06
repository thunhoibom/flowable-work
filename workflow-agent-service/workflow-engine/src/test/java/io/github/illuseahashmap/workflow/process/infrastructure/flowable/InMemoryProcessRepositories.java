package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.port.CallReferenceRepository;
import io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository;
import io.github.illuseahashmap.workflow.process.domain.CallReference;
import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraftHeader;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Port-contract fakes for engine tests that do not need PostgreSQL. */
public final class InMemoryProcessRepositories {

    private InMemoryProcessRepositories() {
    }

    public static final class Drafts implements ProcessDraftRepository {

        private final Map<String, ProcessDraft> rows = new LinkedHashMap<>();

        @Override
        public Optional<ProcessDraft> find(String tenantId, String key) {
            return Optional.ofNullable(rows.get(tenantId + "/" + key));
        }

        @Override
        public Optional<ProcessDraft> findForUpdate(String tenantId, String key) {
            return find(tenantId, key);
        }

        @Override
        public boolean insert(ProcessDraft draft) {
            return rows.putIfAbsent(draft.tenantId() + "/" + draft.processDefinitionKey(), draft) == null;
        }

        @Override
        public boolean update(ProcessDraft draft, long expectedRevision) {
            String id = draft.tenantId() + "/" + draft.processDefinitionKey();
            ProcessDraft current = rows.get(id);
            if (current == null || current.revision() != expectedRevision) {
                return false;
            }
            rows.put(id, draft);
            return true;
        }

        @Override
        public List<ProcessDraftHeader> listHeaders(String tenantId) {
            return rows.values().stream().filter(draft -> draft.tenantId().equals(tenantId))
                    .map(draft -> new ProcessDraftHeader(draft.processDefinitionKey(), draft.name(),
                            draft.xmlSha256(), draft.revision(), draft.updatedBy(), draft.updatedAt()))
                    .toList();
        }

        @Override
        public void delete(String tenantId, String key) {
            rows.remove(tenantId + "/" + key);
        }
    }

    public static final class References implements CallReferenceRepository {

        private final Map<String, List<CallReference>> rows = new LinkedHashMap<>();

        @Override
        public void replaceForDefinition(String tenantId, String parentDefinitionId, List<CallReference> references) {
            rows.put(tenantId + "/" + parentDefinitionId, List.copyOf(references));
        }

        @Override
        public void deleteForDefinition(String tenantId, String parentDefinitionId) {
            rows.remove(tenantId + "/" + parentDefinitionId);
        }

        @Override
        public void deleteForKey(String tenantId, String parentDefinitionKey) {
            rows.entrySet().removeIf(entry -> entry.getKey().startsWith(tenantId + "/")
                    && entry.getValue().stream().anyMatch(ref -> ref.parentDefinitionKey().equals(parentDefinitionKey)));
        }

        @Override
        public List<CallReference> findCallers(String tenantId, String calledKey) {
            return findAll(tenantId).stream().filter(ref -> ref.calledKey().equals(calledKey)).toList();
        }

        @Override
        public List<CallReference> findByDefinition(String tenantId, String parentDefinitionId) {
            return rows.getOrDefault(tenantId + "/" + parentDefinitionId, List.of());
        }

        @Override
        public List<CallReference> findAll(String tenantId) {
            return rows.entrySet().stream().filter(entry -> entry.getKey().startsWith(tenantId + "/"))
                    .flatMap(entry -> entry.getValue().stream()).toList();
        }
    }

    public static final class Releases implements ProcessReleaseRepository {

        private final List<ProcessRelease> rows = new ArrayList<>();
        private long sequence;

        @Override
        public void append(ProcessRelease release) {
            rows.add(new ProcessRelease(++sequence, release.tenantId(), release.processDefinitionKey(),
                    release.processDefinitionId(), release.version(), release.action(), release.sourceVersion(),
                    release.xmlSha256(), release.performedBy(), release.performedAt()));
        }

        @Override
        public List<ProcessRelease> list(String tenantId, String key) {
            return rows.reversed().stream()
                    .filter(row -> row.tenantId().equals(tenantId) && row.processDefinitionKey().equals(key))
                    .toList();
        }

        @Override
        public boolean hasAny(String tenantId, String key) {
            return !list(tenantId, key).isEmpty();
        }

        @Override
        public Map<String, ProcessRelease> latestByDefinition(String tenantId) {
            Map<String, ProcessRelease> latest = new LinkedHashMap<>();
            rows.stream().filter(row -> row.tenantId().equals(tenantId))
                    .forEach(row -> latest.put(row.processDefinitionId(), row));
            return latest;
        }

        @Override
        public void deleteByKey(String tenantId, String key) {
            rows.removeIf(row -> row.tenantId().equals(tenantId) && row.processDefinitionKey().equals(key));
        }
    }
}
