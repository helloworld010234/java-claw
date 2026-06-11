package com.tinyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Workspace security configuration.
 *
 * <p>Controls which directories can be used as agent workspaces via the Web API.
 * The server defines a root directory; client requests may only specify
 * relative sub-paths within that root.</p>
 */
@ConfigurationProperties(prefix = "tiny-claw.workspace")
public class WorkspaceProperties {

    private String root = System.getProperty("user.dir");
    private List<String> allowedIds = List.of("default");

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root != null && !root.isBlank() ? root : System.getProperty("user.dir");
    }

    public List<String> getAllowedIds() {
        return allowedIds;
    }

    public void setAllowedIds(List<String> allowedIds) {
        this.allowedIds = allowedIds != null ? List.copyOf(allowedIds) : List.of();
    }
}
