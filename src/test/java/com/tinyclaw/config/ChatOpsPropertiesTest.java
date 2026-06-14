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

        assertThat(properties.getAppId()).isEmpty();
        assertThat(properties.getAppSecret()).isEmpty();
        assertThat(properties.getVerifyToken()).isEmpty();
        assertThat(properties.getEncryptKey()).isEmpty();
        assertThat(properties.getWorkspace()).isEqualTo("chatops-workspace");
        assertThat(properties.getAllowedChatIds()).isEmpty();
    }

    @Test
    void nonBlankOptionalValuesAreRetained() {
        ChatOpsProperties properties = new ChatOpsProperties();

        properties.setVerifyToken("verify-token");
        properties.setEncryptKey("encrypt-key");
        properties.setWorkspace("ops-workspace");

        assertThat(properties.getVerifyToken()).isEqualTo("verify-token");
        assertThat(properties.getEncryptKey()).isEqualTo("encrypt-key");
        assertThat(properties.getWorkspace()).isEqualTo("ops-workspace");
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
}
