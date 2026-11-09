package io.github.illuseahashmap.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Event registry features. Manual receive tasks never depend on these settings. */
@ConfigurationProperties("workflow.event-registry")
public class EventRegistryProperties {

    /** Whether event models, channels and event waits may be published. */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
