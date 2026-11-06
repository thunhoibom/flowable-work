package io.github.illuseahashmap.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits for embedded subprocesses, event subprocesses and call activities. */
@ConfigurationProperties("workflow.subprocess")
public class SubProcessProperties {

    /** How many subprocesses may be nested inside one another. */
    private int maxNestingDepth = 3;

    /** Whether a published process may contain a call activity. Deployed ones always run guarded. */
    private boolean callActivityEnabled = false;

    /** Longest chain of processes calling one another, the root included. */
    private int maxCallDepth = 5;

    /** Upper bound for in plus out parameters on one call activity. */
    private int maxIoParameters = 64;

    public int getMaxNestingDepth() {
        return maxNestingDepth;
    }

    public void setMaxNestingDepth(int maxNestingDepth) {
        this.maxNestingDepth = maxNestingDepth;
    }

    public boolean isCallActivityEnabled() {
        return callActivityEnabled;
    }

    public void setCallActivityEnabled(boolean callActivityEnabled) {
        this.callActivityEnabled = callActivityEnabled;
    }

    public int getMaxCallDepth() {
        return maxCallDepth;
    }

    public void setMaxCallDepth(int maxCallDepth) {
        this.maxCallDepth = maxCallDepth;
    }

    public int getMaxIoParameters() {
        return maxIoParameters;
    }

    public void setMaxIoParameters(int maxIoParameters) {
        this.maxIoParameters = maxIoParameters;
    }
}
