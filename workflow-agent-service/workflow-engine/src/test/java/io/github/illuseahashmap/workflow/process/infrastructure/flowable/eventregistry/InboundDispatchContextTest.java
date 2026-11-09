package io.github.illuseahashmap.workflow.process.infrastructure.flowable.eventregistry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InboundDispatchContextTest {

    @Test
    void resultFollowsOutcomes() {
        try (var context = InboundDispatchContext.open()) {
            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.NO_MATCH);
            context.recordSuccess("p1", "wait");
            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.MATCHED);
            context.recordFailure("p2", "wait", "CONCURRENT_UPDATE");
            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.PARTIAL);
        }
        try (var context = InboundDispatchContext.open()) {
            context.recordFailure("p2", "wait", "EVENT_PROCESSING_FAILED");
            assertThat(context.result()).isEqualTo(InboundDispatchContext.Result.FAILED);
        }
    }

    @Test
    void contextIsThreadBoundAndClosed() {
        assertThat(InboundDispatchContext.current()).isEmpty();
        try (var context = InboundDispatchContext.open()) {
            assertThat(InboundDispatchContext.current()).containsSame(context);
        }
        assertThat(InboundDispatchContext.current()).isEmpty();
    }
}
