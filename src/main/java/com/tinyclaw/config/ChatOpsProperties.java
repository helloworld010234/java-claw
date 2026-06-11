package com.tinyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * ChatOps configuration properties.
 *
 * <p>Default: disabled. When enabled, requires app-id / token / signing-secret
 * placeholders to be set explicitly.</p>
 */
@ConfigurationProperties(prefix = "tiny-claw.chatops")
public class ChatOpsProperties {

    private boolean enabled = false;
    private String appId = "";
    private String appSecret = "";
    private String verifyToken = "";
    private String encryptKey = "";
    private String workspace = "chatops-workspace";
    private List<String> allowedChatIds = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId != null ? appId : "";
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret != null ? appSecret : "";
    }

    public String getVerifyToken() {
        return verifyToken;
    }

    public void setVerifyToken(String verifyToken) {
        this.verifyToken = verifyToken != null ? verifyToken : "";
    }

    public String getEncryptKey() {
        return encryptKey;
    }

    public void setEncryptKey(String encryptKey) {
        this.encryptKey = encryptKey != null ? encryptKey : "";
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace != null && !workspace.isBlank() ? workspace : "chatops-workspace";
    }

    public List<String> getAllowedChatIds() {
        return allowedChatIds;
    }

    public void setAllowedChatIds(List<String> allowedChatIds) {
        this.allowedChatIds = allowedChatIds != null ? List.copyOf(allowedChatIds) : List.of();
    }

    /**
     * Returns true if the basic required placeholders are non-blank.
     * Used to guard startup when ChatOps is enabled but unconfigured.
     */
    public boolean isConfigured() {
        return enabled && !appId.isBlank() && !appSecret.isBlank();
    }
}
