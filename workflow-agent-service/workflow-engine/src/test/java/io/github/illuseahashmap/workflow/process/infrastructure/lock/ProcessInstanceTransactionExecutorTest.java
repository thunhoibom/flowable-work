package io.github.illuseahashmap.workflow.process.infrastructure.lock;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.workflow.process.application.port.ProcessInstanceLock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class ProcessInstanceTransactionExecutorTest {

    @Test
    void commitsTransactionBeforeReleasingProcessLock() {
        List<String> events = new ArrayList<>();
        ProcessInstanceLock lock = new RecordingLock(events);
        ProcessInstanceTransactionExecutor executor = new ProcessInstanceTransactionExecutor(
                lock, new RecordingTransactionManager(events), id -> id);

        String result = executor.execute("process-1", () -> {
            events.add("operation");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(events).containsExactly("lock-acquired:process-1", "transaction-begin", "operation",
                "transaction-commit", "lock-released");
    }

    @Test
    void locksTheRootInstanceSoParentAndChildCommandsDoNotOverlap() {
        List<String> events = new ArrayList<>();
        ProcessInstanceTransactionExecutor executor = new ProcessInstanceTransactionExecutor(
                new RecordingLock(events), new RecordingTransactionManager(events),
                id -> "child-1".equals(id) ? "root-1" : id);

        executor.execute("child-1", () -> {
            events.add("rootInMdc:" + org.slf4j.MDC.get("rootProcessInstanceId"));
            return null;
        });

        assertThat(events).contains("lock-acquired:root-1", "rootInMdc:root-1");
        assertThat(org.slf4j.MDC.get("rootProcessInstanceId")).isNull();
    }

    private record RecordingLock(List<String> events) implements ProcessInstanceLock {

        @Override
        public <T> T execute(String processInstanceId, Supplier<T> operation) {
            events.add("lock-acquired:" + processInstanceId);
            try {
                return operation.get();
            } finally {
                events.add("lock-released");
            }
        }
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final List<String> events;

        private RecordingTransactionManager(List<String> events) {
            this.events = events;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("transaction-begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("transaction-commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("transaction-rollback");
        }
    }
}
