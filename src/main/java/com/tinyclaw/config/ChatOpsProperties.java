package com.tinyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * ChatOps configuration properties.
 *
 * <p>Default: disabled. When enabled, requires app-id and app-secret to be set
 * explicitly. Other settings use sensible defaults for Feishu OpenAPI.</p>
 */
@ConfigurationProperties(prefix = "tiny-claw.chatops")
public class ChatOpsProperties {

    /** Default Feishu OpenAPI origin. */
    public static final String DEFAULT_BASE_URL = "https://open.feishu.cn";

    /** Default HTTP request timeout for Feishu calls. */
    public static final long DEFAULT_REQUEST_TIMEOUT_SECONDS = 10;

    /** Default token refresh skew. */
    public static final long DEFAULT_TOKEN_REFRESH_SKEW_SECONDS = 300;

    /** Hosts allowed for the Feishu OpenAPI base URL. */
    public static final Set<String> ALLOWED_BASE_URL_HOSTS = Set.of(
        "open.feishu.cn",
        "open.larksuite.com"
    );

    private boolean enabled = false;
    private String appId = "";
    private String appSecret = "";
    private String verifyToken = "";
    private String encryptKey = "";
    private String workspace = "chatops-workspace";
    private List<String> allowedChatIds = List.of();
    private String baseUrl = DEFAULT_BASE_URL;
    private long requestTimeoutSeconds = DEFAULT_REQUEST_TIMEOUT_SECONDS;
    private long tokenRefreshSkewSeconds = DEFAULT_TOKEN_REFRESH_SKEW_SECONDS;

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

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = normalizeAndValidateBaseUrl(baseUrl);
    }

    private String normalizeAndValidateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        String trimmed = baseUrl.trim();
        try {
            URI uri = URI.create(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return DEFAULT_BASE_URL;
            }
            String host = uri.getHost();
            if (host == null || !ALLOWED_BASE_URL_HOSTS.contains(host.toLowerCase())) {
                return DEFAULT_BASE_URL;
            }
            if (trimmed.endsWith("/")) {
                return trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed;
        } catch (Exception e) {
            return DEFAULT_BASE_URL;
        }
    }

    public long getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(long requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds > 0
            ? requestTimeoutSeconds
            : DEFAULT_REQUEST_TIMEOUT_SECONDS;
    }

    public long getTokenRefreshSkewSeconds() {
        return tokenRefreshSkewSeconds;
    }

    public void setTokenRefreshSkewSeconds(long tokenRefreshSkewSeconds) {
        this.tokenRefreshSkewSeconds = tokenRefreshSkewSeconds >= 0
            ? tokenRefreshSkewSeconds
            : DEFAULT_TOKEN_REFRESH_SKEW_SECONDS;
    }

    /**
     * Returns true if the basic required placeholders are non-blank.
     * Used to guard startup when ChatOps is enabled but unconfigured.
     */
    public boolean isConfigured() {
        return enabled && !appId.isBlank() && !appSecret.isBlank();
    }
}
