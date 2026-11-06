package io.github.illuseahashmap.workflow.process.infrastructure.lock;

import io.github.illuseahashmap.workflow.process.application.port.ProcessInstanceLock;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Serializes process-instance commands and commits their transaction before releasing the lock.
 *
 * <p>The lock is taken on the root instance. Completing the last task of a child started by a
 * call activity continues the parent in the same transaction, so parent and child commands must
 * not run side by side.
 */
@Component
public class ProcessInstanceTransactionExecutor {

    static final String ROOT_MDC_KEY = "rootProcessInstanceId";

    private final ProcessInstanceLock processInstanceLock;
    private final TransactionTemplate transactionTemplate;
    private final UnaryOperator<String> rootOf;

    @Autowired
    public ProcessInstanceTransactionExecutor(ProcessInstanceLock processInstanceLock,
                                              PlatformTransactionManager transactionManager,
                                              RuntimeService runtimeService) {
        this(processInstanceLock, transactionManager, id -> rootOf(runtimeService, id));
    }

    ProcessInstanceTransactionExecutor(ProcessInstanceLock processInstanceLock,
                                       PlatformTransactionManager transactionManager,
                                       UnaryOperator<String> rootOf) {
        this.processInstanceLock = processInstanceLock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.rootOf = rootOf;
    }

    public <T> T execute(String processInstanceId, Supplier<T> operation) {
        String root = rootOf.apply(processInstanceId);
        return processInstanceLock.execute(root, () -> {
            try (MDC.MDCCloseable ignored = MDC.putCloseable(ROOT_MDC_KEY, root)) {
                return transactionTemplate.execute(status -> operation.get());
            }
        });
    }

    /** A finished or unknown instance has no running tree: lock on its own id. */
    private static String rootOf(RuntimeService runtimeService, String processInstanceId) {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        return instance == null || instance.getRootProcessInstanceId() == null
                ? processInstanceId
                : instance.getRootProcessInstanceId();
    }
}
