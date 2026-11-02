package io.github.illuseahashmap.workflow.serviceregistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.serviceregistry.application.PublishedServiceInvoker;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.CallbackWait;
import io.github.illuseahashmap.serviceregistry.domain.CallbackWaitStore;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.OutputParameter;
import io.github.illuseahashmap.serviceregistry.domain.ParameterType;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.workflow.process.application.ServiceParameterException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServiceCallbackServiceTest {

    @Mock
    private PublishedServiceInvoker invoker;
    @Mock
    private ServiceCallbackSignals signals;

    @Test
    void usesTheCallbackBodyAsTheResponseAndIgnoresAnUnknownId() {
        var clock = new MutableClock(Instant.parse("2026-11-02T00:00:00Z"));
        var waits = new Memory();
        var service = new ServiceCallbackService(waits, invoker, signals, clock);
        service.register("abc", "exec-1", "crm", "createLead", Map.of("id", "leadId"), 5_000);
        when(invoker.findPublished("crm", "createLead")).thenReturn(Optional.of(new PublishedOperation(
                "createLead", HttpMethod.POST, "https://crm.example/leads",
                AuthType.NONE, null, null, null, 1000, List.of(), "HTTP_ERROR",
                List.of(),
                List.of(new OutputParameter("id", ParameterType.STRING, "id")))));

        assertTrue(service.complete("abc", "{\"id\":\"L1\"}", Map.of("Content-Type", "application/json"), null));

        verify(signals).trigger("exec-1", Map.of("leadId", "L1"));
        assertFalse(service.complete("abc", "{}", Map.of(), null));
    }

    @Test
    void rejectsADuplicateOrUnsafeCorrelationIdAndExpiresTheWait() {
        var clock = new MutableClock(Instant.parse("2026-11-02T00:00:00Z"));
        var waits = new Memory();
        var service = new ServiceCallbackService(waits, invoker, signals, clock);
        service.register("abc", "exec-1", "crm", "createLead", Map.of(), 1_000);
        assertThrows(ServiceParameterException.class,
                () -> service.register("abc", "exec-2", "crm", "createLead", Map.of(), 1_000));
        assertThrows(ServiceParameterException.class,
                () -> service.register("a/b", "exec-2", "crm", "createLead", Map.of(), 1_000));

        clock.now = clock.now.plusSeconds(2);
        service.expire();
        verify(signals).fail("exec-1");
        assertEquals(0, waits.rows.size());
    }

    private static final class Memory implements CallbackWaitStore {
        private final Map<String, CallbackWait> rows = new LinkedHashMap<>();

        @Override
        public void insert(CallbackWait wait) {
            if (rows.containsKey(wait.correlationId())) {
                throw new IllegalStateException("Correlation id is already waiting");
            }
            rows.put(wait.correlationId(), wait);
        }

        @Override
        public Optional<CallbackWait> take(String correlationId) {
            return Optional.ofNullable(rows.remove(correlationId));
        }

        @Override
        public List<CallbackWait> takeExpired(long nowEpochMs) {
            List<CallbackWait> expired = new ArrayList<>();
            rows.values().stream()
                    .filter(wait -> wait.deadlineEpochMs() <= nowEpochMs)
                    .forEach(expired::add);
            expired.forEach(wait -> rows.remove(wait.correlationId()));
            return expired;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
