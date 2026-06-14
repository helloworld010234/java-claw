package com.tinyclaw.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatOpsPropertiesTest {

    @Test
    void defaultsAreDisabledAndUnconfigured() {
        ChatOpsProperties properties = new ChatOpsProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getAppId()).isEmpty();
        assertThat(properties.getAppSecret()).isEmpty();
        assertThat(properties.getVerifyToken()).isEmpty();
        assertThat(properties.getEncryptKey()).isEmpty();
        assertThat(properties.getWorkspace()).isEqualTo("chatops-workspace");
        assertThat(properties.getAllowedChatIds()).isEmpty();
        assertThat(properties.getBaseUrl()).isEqualTo("https://open.feishu.cn");
        assertThat(properties.getRequestTimeoutSeconds()).isEqualTo(10);
        assertThat(properties.getTokenRefreshSkewSeconds()).isEqualTo(300);
        assertThat(properties.isConfigured()).isFalse();
    }

    @Test
    void nullAndBlankSettersUseSafeDefaults() {
        ChatOpsProperties properties = new ChatOpsProperties();

        properties.setAppId(null);
        properties.setAppSecret(null);
        properties.setVerifyToken(null);
        properties.setEncryptKey(null);
        properties.setWorkspace("   ");
        properties.setAllowedChatIds(null);
        properties.setBaseUrl(null);
        properties.setRequestTimeoutSeconds(-5);
        properties.setTokenRefreshSkewSeconds(-1);

        assertThat(properties.getAppId()).isEmpty();
        assertThat(properties.getAppSecret()).isEmpty();
        assertThat(properties.getVerifyToken()).isEmpty();
        assertThat(properties.getEncryptKey()).isEmpty();
        assertThat(properties.getWorkspace()).isEqualTo("chatops-workspace");
        assertThat(properties.getAllowedChatIds()).isEmpty();
        assertThat(properties.getBaseUrl()).isEqualTo("https://open.feishu.cn");
        assertThat(properties.getRequestTimeoutSeconds()).isEqualTo(10);
        assertThat(properties.getTokenRefreshSkewSeconds()).isEqualTo(300);
    }

    @Test
    void nonBlankOptionalValuesAreRetained() {
        ChatOpsProperties properties = new ChatOpsProperties();

        properties.setVerifyToken("verify-token");
        properties.setEncryptKey("encrypt-key");
        properties.setWorkspace("ops-workspace");
        properties.setBaseUrl("https://open.feishu.cn/open-apis");
        properties.setRequestTimeoutSeconds(30);
        properties.setTokenRefreshSkewSeconds(60);

        assertThat(properties.getVerifyToken()).isEqualTo("verify-token");
        assertThat(properties.getEncryptKey()).isEqualTo("encrypt-key");
        assertThat(properties.getWorkspace()).isEqualTo("ops-workspace");
        assertThat(properties.getBaseUrl()).isEqualTo("https://open.feishu.cn/open-apis");
        assertThat(properties.getRequestTimeoutSeconds()).isEqualTo(30);
        assertThat(properties.getTokenRefreshSkewSeconds()).isEqualTo(60);
    }

    @Test
    void configuredRequiresEnabledAppIdAndSecret() {
        ChatOpsProperties properties = new ChatOpsProperties();

        properties.setAppId("app");
        properties.setAppSecret("secret");
        assertThat(properties.isConfigured()).isFalse();

        properties.setEnabled(true);
        assertThat(properties.isConfigured()).isTrue();

        properties.setAppSecret(" ");
        assertThat(properties.isConfigured()).isFalse();
    }

    @Test
    void allowedChatIdsAreCopiedDefensively() {
        ChatOpsProperties properties = new ChatOpsProperties();
        List<String> ids = new ArrayList<>(List.of("chat-1", "chat-2"));

        properties.setAllowedChatIds(ids);
        ids.add("chat-3");

        assertThat(properties.getAllowedChatIds()).containsExactly("chat-1", "chat-2");
        assertThatThrownBy(() -> properties.getAllowedChatIds().add("chat-4"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void newPropertiesDoNotAffectConfiguredFlag() {
        ChatOpsProperties properties = new ChatOpsProperties();

        properties.setEnabled(true);
        properties.setAppId("app");
        properties.setAppSecret("secret");
        assertThat(properties.isConfigured()).isTrue();

        properties.setBaseUrl("   ");
        properties.setRequestTimeoutSeconds(-1);
        properties.setTokenRefreshSkewSeconds(-10);

        assertThat(properties.isConfigured()).isTrue();
    }

    @Test
    void requestTimeoutSecondsZeroFallsBackToDefault() {
        ChatOpsProperties properties = new ChatOpsProperties();
        properties.setRequestTimeoutSeconds(0);
        assertThat(properties.getRequestTimeoutSeconds()).isEqualTo(ChatOpsProperties.DEFAULT_REQUEST_TIMEOUT_SECONDS);
    }

    @Test
    void requestTimeoutSecondsNegativeFallsBackToDefault() {
        ChatOpsProperties properties = new ChatOpsProperties();
        properties.setRequestTimeoutSeconds(-10);
        assertThat(properties.getRequestTimeoutSeconds()).isEqualTo(ChatOpsProperties.DEFAULT_REQUEST_TIMEOUT_SECONDS);
    }

    @Test
    void validRequestTimeoutSecondsIsRetained() {
        ChatOpsProperties properties = new ChatOpsProperties();
        properties.setRequestTimeoutSeconds(45);
        assertThat(properties.getRequestTimeoutSeconds()).isEqualTo(45);
    }

    @Test
    void baseUrlWithTrailingSlashIsNormalized() {
        ChatOpsProperties properties = new ChatOpsProperties();
        properties.setBaseUrl("https://open.feishu.cn/");
        assertThat(properties.getBaseUrl()).isEqualTo("https://open.feishu.cn");
    }

    @Test
    void httpBaseUrlFallsBackToDefault() {
        ChatOpsProperties properties = new ChatOpsProperties();
        properties.setBaseUrl("http://open.feishu.cn");
        assertThat(properties.getBaseUrl()).isEqualTo(ChatOpsProperties.DEFAULT_BASE_URL);
    }

    @Test
    void nonAllowedHostFallsBackToDefault() {
        ChatOpsProperties properties = new ChatOpsProperties();
        properties.setBaseUrl("https://evil.example.com");
        assertThat(properties.getBaseUrl()).isEqualTo(ChatOpsProperties.DEFAULT_BASE_URL);
    }

    @Test
    void malformedBaseUrlFallsBackToDefault() {
        ChatOpsProperties properties = new ChatOpsProperties();
        properties.setBaseUrl("not-a-url");
        assertThat(properties.getBaseUrl()).isEqualTo(ChatOpsProperties.DEFAULT_BASE_URL);
    }

    @Test
    void blankBaseUrlFallsBackToDefault() {
        ChatOpsProperties properties = new ChatOpsProperties();
        properties.setBaseUrl("   ");
        assertThat(properties.getBaseUrl()).isEqualTo(ChatOpsProperties.DEFAULT_BASE_URL);
    }

    @Test
    void larkBaseUrlIsAllowed() {
        ChatOpsProperties properties = new ChatOpsProperties();
        properties.setBaseUrl("https://open.larksuite.com");
        assertThat(properties.getBaseUrl()).isEqualTo("https://open.larksuite.com");
    }
}
