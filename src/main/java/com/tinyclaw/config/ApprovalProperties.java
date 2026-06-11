package com.tinyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for the approval gate.
 */
@ConfigurationProperties(prefix = "tinyclaw.approval")
public class ApprovalProperties {

    private boolean enabled = true;
    private List<String> requiredTools = List.of("write_file", "edit_file", "shell_command");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getRequiredTools() {
        return requiredTools;
    }

    public void setRequiredTools(List<String> requiredTools) {
        this.requiredTools = requiredTools != null ? List.copyOf(requiredTools) : List.of();
    }
}
