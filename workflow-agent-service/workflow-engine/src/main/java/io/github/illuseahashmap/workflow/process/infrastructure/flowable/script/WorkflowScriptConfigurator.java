package io.github.illuseahashmap.workflow.process.infrastructure.flowable.script;

import io.github.illuseahashmap.workflow.config.ScriptTaskProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.flowable.common.engine.impl.AbstractEngineConfiguration;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.parse.BpmnParseHandler;
import org.flowable.scripting.secure.SecureJavascriptConfigurator;

/**
 * Uses Flowable's secure Rhino context factory (limits and class shutter) but registers our own parse
 * handler, because SecureJavascriptTaskActivityBehavior ignores flowable:in and exposes execution.
 */
public class WorkflowScriptConfigurator extends SecureJavascriptConfigurator {

    public WorkflowScriptConfigurator(ScriptTaskProperties properties) {
        setEnableClassWhiteListing(true);
        setWhiteListedClasses(new HashSet<>());
        setEnableAccessToBeans(false);
        setScriptOptimizationLevel(-1);
        setMaxScriptExecutionTime(properties.getMaxExecutionMillis());
        setMaxMemoryUsed(properties.getMaxMemoryBytes());
        setMaxStackDepth(properties.getMaxStackDepth());
        setNrOfInstructionsBeforeStateCheckCallback(properties.getInstructionCheckInterval());
    }

    @Override
    public void beforeInit(AbstractEngineConfiguration engineConfiguration) {
        if (secureScriptContextFactory == null) {
            initSecureScriptContextFactory();
        }
        ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) engineConfiguration;
        List<BpmnParseHandler> handlers = configuration.getCustomDefaultBpmnParseHandlers();
        if (handlers == null) {
            handlers = new ArrayList<>();
            configuration.setCustomDefaultBpmnParseHandlers(handlers);
        }
        handlers.add(new WorkflowScriptTaskParseHandler());
    }
}
