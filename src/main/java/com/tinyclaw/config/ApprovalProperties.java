package com.tinyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for the approval gate.
 */
@ConfigurationProperties(prefix = "tinyclaw.approval")
public class ApprovalProperties {

    private List<String> requiredTools = List.of();

    public List<String> getRequiredTools() {
        return requiredTools;
    }

    public void setRequiredTools(List<String> requiredTools) {
        this.requiredTools = requiredTools != null ? List.copyOf(requiredTools) : List.of();
    }
}
