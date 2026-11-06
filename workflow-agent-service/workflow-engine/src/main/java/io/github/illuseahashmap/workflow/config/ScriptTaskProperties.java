package io.github.illuseahashmap.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits for the sandboxed JavaScript script task. */
@ConfigurationProperties("workflow.script-task")
public class ScriptTaskProperties {

    /** Whether deployments may contain a script task. Deployed script tasks always run sandboxed. */
    private boolean enabled = false;
    private int maxScriptLength = 20000;
    private int maxInputs = 32;
    private long maxExecutionMillis = 2000L;
    private long maxMemoryBytes = 8388608L;
    private int maxStackDepth = 64;
    private int instructionCheckInterval = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxScriptLength() {
        return maxScriptLength;
    }

    public void setMaxScriptLength(int maxScriptLength) {
        this.maxScriptLength = maxScriptLength;
    }

    public int getMaxInputs() {
        return maxInputs;
    }

    public void setMaxInputs(int maxInputs) {
        this.maxInputs = maxInputs;
    }

    public long getMaxExecutionMillis() {
        return maxExecutionMillis;
    }

    public void setMaxExecutionMillis(long maxExecutionMillis) {
        this.maxExecutionMillis = maxExecutionMillis;
    }

    public long getMaxMemoryBytes() {
        return maxMemoryBytes;
    }

    public void setMaxMemoryBytes(long maxMemoryBytes) {
        this.maxMemoryBytes = maxMemoryBytes;
    }

    public int getMaxStackDepth() {
        return maxStackDepth;
    }

    public void setMaxStackDepth(int maxStackDepth) {
        this.maxStackDepth = maxStackDepth;
    }

    public int getInstructionCheckInterval() {
        return instructionCheckInterval;
    }

    public void setInstructionCheckInterval(int instructionCheckInterval) {
        this.instructionCheckInterval = instructionCheckInterval;
    }
}
