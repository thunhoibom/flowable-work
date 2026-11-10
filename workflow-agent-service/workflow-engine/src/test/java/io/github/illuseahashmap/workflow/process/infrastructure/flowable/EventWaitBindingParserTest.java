package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.Test;

class EventWaitBindingParserTest {

    private static List<EventWaitBinding> parse(String body) {
        return EventWaitBindingParser.parse(EventRegistryDeploymentGuardTest.bpmn(body));
    }

    @Test
    void findsTheThreeKindsWithCorrelationsAndOutputsInOrder() {
        List<EventWaitBinding> bindings = parse("""
                <receiveTask id="wait"><extensionElements>
                  <flowable:eventType> paymentReceived </flowable:eventType>
                  <flowable:eventCorrelationParameter name="orderId" value="${orderId}"/>
                  <flowable:eventCorrelationParameter name="region" value="${region}"/>
                  <flowable:eventOutParameter source="amount" target="paidAmount"/>
                  <flowable:eventOutParameter source="note" target="note" transient="TRUE"/>
                </extensionElements></receiveTask>
                <intermediateCatchEvent id="catch"><extensionElements>
                  <activiti:eventType>shipped</activiti:eventType>
                </extensionElements></intermediateCatchEvent>
                <userTask id="work"/>
                <boundaryEvent id="b" attachedToRef="work" cancelActivity="false"><extensionElements>
                  <flowable:eventType>cancelled</flowable:eventType>
                </extensionElements></boundaryEvent>""");

        assertThat(bindings).extracting(EventWaitBinding::activityId, EventWaitBinding::kind, EventWaitBinding::eventKey)
                .containsExactly(
                        tuple("wait", EventWaitBinding.Kind.RECEIVE_TASK,
                                "paymentReceived"),
                        tuple("catch", EventWaitBinding.Kind.INTERMEDIATE_CATCH,
                                "shipped"),
                        tuple("b", EventWaitBinding.Kind.BOUNDARY, "cancelled"));
        EventWaitBinding wait = bindings.getFirst();
        assertThat(wait.correlations()).containsExactly(
                new EventWaitBinding.Correlation("orderId", "${orderId}"),
                new EventWaitBinding.Correlation("region", "${region}"));
        assertThat(wait.outputs()).containsExactly(
                new EventWaitBinding.Output("amount", "paidAmount", false),
                new EventWaitBinding.Output("note", "note", true));
        assertThat(wait.agentTask()).isFalse();
        assertThat(bindings.get(2).attachedToRef()).isEqualTo("work");
        assertThat(bindings).noneMatch(EventWaitBinding::hasEventDefinition);
    }

    @Test
    void ignoresElementsWithoutEventType() {
        assertThat(parse("""
                <receiveTask id="manual"/>
                <intermediateCatchEvent id="timer">
                  <timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition>
                </intermediateCatchEvent>
                <userTask id="u"><extensionElements><flowable:eventType>x</flowable:eventType></extensionElements>
                </userTask>""")).isEmpty();
    }

    @Test
    void returnsBlankEventTypeSoItCanBeReported() {
        assertThat(parse("""
                <receiveTask id="wait"><extensionElements><flowable:eventType>  </flowable:eventType>
                </extensionElements></receiveTask>"""))
                .singleElement().extracting(EventWaitBinding::eventKey).isEqualTo("");
    }

    @Test
    void detectsEventDefinitionAndAgentTask() {
        List<EventWaitBinding> bindings = parse("""
                <userTask id="work"/>
                <boundaryEvent id="b" attachedToRef="work">
                  <extensionElements><flowable:eventType>k</flowable:eventType></extensionElements>
                  <timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition>
                </boundaryEvent>
                <receiveTask id="agent"><extensionElements>
                  <workflow:agentTask agentVersionId="1"/>
                  <flowable:eventType>k</flowable:eventType>
                </extensionElements></receiveTask>""");

        assertThat(bindings.get(0).hasEventDefinition()).isTrue();
        assertThat(bindings.get(1).agentTask()).isTrue();
    }
}
