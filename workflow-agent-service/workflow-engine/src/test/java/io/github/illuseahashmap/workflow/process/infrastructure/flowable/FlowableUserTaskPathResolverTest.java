package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.common.engine.impl.el.DefaultExpressionManager;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FlowableUserTaskPathResolverTest {

    private static final String WORKFLOW_NAMESPACE = "http://workflow-agent.local/bpmn";

    @Mock
    private RepositoryService repositoryService;
    @Mock
    private ProcessEngineConfigurationImpl processEngineConfiguration;

    private FlowableUserTaskPathResolver resolver;

    @BeforeEach
    void setUp() {
        when(processEngineConfiguration.getExpressionManager())
                .thenReturn(new DefaultExpressionManager(Map.of()));
        resolver = new FlowableUserTaskPathResolver(repositoryService, processEngineConfiguration);
    }

    @Test
    void serviceCallSharingTheWorkflowNamespaceIsNotAnAgentTask() {
        when(repositoryService.getBpmnModel("def")).thenReturn(serviceCallModel());

        assertThat(resolver.firstAgentTasks("def", Map.of())).isEmpty();
    }

    @Test
    void agentTaskInTheWorkflowNamespaceRemainsAnAgentTask() {
        when(repositoryService.getBpmnModel("def")).thenReturn(agentTaskModel());

        assertThat(resolver.firstAgentTasks("def", Map.of()))
                .extracting(element -> element.getId())
                .containsExactly("review");
    }

    private BpmnModel serviceCallModel() {
        ServiceTask service = new ServiceTask();
        service.setId("Activity_19d9nv0");
        service.setName("approve");
        service.addExtensionElement(extension("serviceCall"));
        return linearModel(service);
    }

    private BpmnModel agentTaskModel() {
        ReceiveTask agent = new ReceiveTask();
        agent.setId("review");
        agent.addExtensionElement(extension("agentTask"));
        return linearModel(agent);
    }

    private ExtensionElement extension(String name) {
        ExtensionElement element = new ExtensionElement();
        element.setName(name);
        element.setNamespace(WORKFLOW_NAMESPACE);
        return element;
    }

    private BpmnModel linearModel(FlowNode activity) {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("Process_0cqiom7");
        StartEvent start = new StartEvent();
        start.setId("start");
        EndEvent end = new EndEvent();
        end.setId("end");
        SequenceFlow toActivity = new SequenceFlow("start", activity.getId());
        toActivity.setSourceFlowElement(start);
        toActivity.setTargetFlowElement(activity);
        SequenceFlow toEnd = new SequenceFlow(activity.getId(), "end");
        toEnd.setSourceFlowElement(activity);
        toEnd.setTargetFlowElement(end);
        start.setOutgoingFlows(List.of(toActivity));
        activity.setIncomingFlows(List.of(toActivity));
        activity.setOutgoingFlows(List.of(toEnd));
        end.setIncomingFlows(List.of(toEnd));
        process.addFlowElement(start);
        process.addFlowElement(activity);
        process.addFlowElement(end);
        process.addFlowElement(toActivity);
        process.addFlowElement(toEnd);
        model.addProcess(process);
        return model;
    }
}
