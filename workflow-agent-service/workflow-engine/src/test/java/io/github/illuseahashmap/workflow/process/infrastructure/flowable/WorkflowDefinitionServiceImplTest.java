package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.process.application.ProcessPublishService;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessResult;
import io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository;
import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import java.util.List;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;

class WorkflowDefinitionServiceImplTest {

    @Test
    void deployPublishesThroughThePublishService() {
        RepositoryService repositoryService = mock(RepositoryService.class);
        ProcessPublishService publishService = mock(ProcessPublishService.class);
        WorkflowDefinitionServiceImpl service = new WorkflowDefinitionServiceImpl(
                repositoryService, mock(TenantProvider.class), publishService,
                mock(ProcessReleaseRepository.class), mock(ProcessDraftRepository.class),
                new ProcessDefinitionLookup(repositoryService));
        when(publishService.publishXml("p", "P", "<xml/>"))
                .thenReturn(new PublishProcessResult("d1", "p:1:1", "p", "P", 1, null, List.of()));

        var result = service.deploy(new DeployProcessRequest(" p ", " P ", "<xml/>"));

        verify(publishService).publishXml("p", "P", "<xml/>");
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.processDefinitionId()).isEqualTo("p:1:1");
        verifyNoInteractions(repositoryService);
    }
}
