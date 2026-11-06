package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.config.ProcessDefinitionProperties;
import io.github.illuseahashmap.workflow.process.application.dto.CreateProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftRevisionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.SaveProcessDraftRequest;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.Set;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcessDraftServiceImplTest {

    static final String TENANT = "tenant-draft";

    static String xml(String key, String marker) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="s"/><endEvent id="e"/><sequenceFlow id="f" sourceRef="s" targetRef="e"/>
                  </process>
                </definitions>""".formatted(key, marker);
    }

    private static ProcessEngine engine;
    private ProcessDraftServiceImpl service;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:draft-service;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @BeforeEach
    void setUp() {
        service = new ProcessDraftServiceImpl(new InMemoryProcessRepositories.Drafts(),
                new ProcessDefinitionLookup(engine.getRepositoryService()),
                new BpmnDraftInspector(new ProcessDefinitionProperties()),
                () -> new TenantContext.TenantInfo(TENANT, "draft", "Draft"),
                () -> new CurrentPrincipal("USER", "u1", "alice", "Alice", "draft", Set.of(), Set.of()));
    }

    private static void deploy(String key, String marker) {
        engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString(key + ".bpmn20.xml", xml(key, marker)).deploy();
    }

    @Test
    void createsAndSavesWithoutDeploying() {
        var created = service.create(new CreateProcessDraftRequest("d1", "D1", xml("d1", "v-a")));
        assertThat(created.revision()).isEqualTo(1);
        assertThat(created.latestVersion()).isNull();
        assertThat(created.hasUnpublishedChanges()).isTrue();

        var saved = service.save("d1", new SaveProcessDraftRequest("D1", xml("d1", "v-b"), 1));

        assertThat(saved.revision()).isEqualTo(2);
        assertThat(saved.updatedBy()).isEqualTo("alice");
        assertThat(engine.getRepositoryService().createDeploymentQuery().deploymentTenantId(TENANT)
                .deploymentKey("d1").count()).isZero();
    }

    @Test
    void rejectsAStaleRevision() {
        service.create(new CreateProcessDraftRequest("d2", "D2", xml("d2", "a")));
        service.save("d2", new SaveProcessDraftRequest("D2", xml("d2", "b"), 1));

        assertThatThrownBy(() -> service.save("d2", new SaveProcessDraftRequest("D2", xml("d2", "c"), 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DRAFT_REVISION_CONFLICT));
    }

    @Test
    void rejectsAKeyThatExistsInTheEngineOrAsADraft() {
        deploy("d3", "x");
        service.create(new CreateProcessDraftRequest("d3b", "D3b", xml("d3b", "y")));

        assertThatThrownBy(() -> service.create(new CreateProcessDraftRequest("d3", "D3", xml("d3", "y"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> service.create(new CreateProcessDraftRequest("d3b", "D3b", xml("d3b", "z"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void rejectsXmlForAnotherKey() {
        assertThatThrownBy(() -> service.create(new CreateProcessDraftRequest("d6", "D6", xml("other", "x"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("process id");
    }

    @Test
    void tracksUnpublishedChangesAndLoadsVersions() {
        service.create(new CreateProcessDraftRequest("d4", "D4", xml("d4", "one")));
        deploy("d4", "one");
        assertThat(service.get("d4").hasUnpublishedChanges()).isFalse();
        assertThat(service.get("d4").latestVersion()).isEqualTo(1);

        service.save("d4", new SaveProcessDraftRequest("D4", xml("d4", "two"), 1));
        assertThat(service.get("d4").hasUnpublishedChanges()).isTrue();

        var discarded = service.discard("d4", new DraftRevisionRequest(2));
        assertThat(discarded.hasUnpublishedChanges()).isFalse();
        assertThat(discarded.bpmnXml()).contains("one");

        service.save("d4", new SaveProcessDraftRequest("D4", xml("d4", "three"), discarded.revision()));
        var loaded = service.loadVersion("d4", new DraftVersionRequest(1, discarded.revision() + 1));
        assertThat(loaded.bpmnXml()).contains("one");
    }

    @Test
    void getFallsBackToTheLatestVersionWhenNoDraftExists() {
        deploy("d5", "legacy");

        var view = service.get("d5");
        assertThat(view.revision()).isZero();
        assertThat(view.bpmnXml()).contains("legacy");

        var saved = service.save("d5", new SaveProcessDraftRequest("D5", xml("d5", "edited"), 0));
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(saved.hasUnpublishedChanges()).isTrue();
    }

    @Test
    void unknownKeyIsNotFound() {
        assertThatThrownBy(() -> service.get("missing"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.save("missing", new SaveProcessDraftRequest("M", xml("missing", "x"), 0)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
