package com.tinyclaw.ports.chatops;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatOpsOutboundMessageTest {

    @Test
    void runStartedMasksSecretInPrompt() {
        String prompt = "my api_key is sk-live-12345";
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.runStarted("run-1", prompt);
        assertThat(msg.text()).doesNotContain("sk-live-12345");
        assertThat(msg.text()).contains("***");
    }

    @Test
    void runStartedHandlesNullPrompt() {
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.runStarted("run-1", null);
        assertThat(msg.text()).isEqualTo("▶️ Run started");
    }

    @Test
    void runStartedHandlesBlankPrompt() {
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.runStarted("run-1", "   ");
        assertThat(msg.text()).isEqualTo("▶️ Run started");
    }

    @Test
    void toolCallMasksSecretInArgs() {
        String args = "{\"api_key\":\"sk-secret\",\"command\":\"echo hi\"}";
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.toolCall("run-1", "shell_command", args);
        assertThat(msg.text()).doesNotContain("sk-secret");
        assertThat(msg.text()).contains("***");
    }

    @Test
    void toolResultMasksSecretInOutput() {
        String output = "{\"access_token\":\"tok-123\",\"status\":\"ok\"}";
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.toolResult("run-1", "api_call", false, output);
        assertThat(msg.text()).doesNotContain("tok-123");
        assertThat(msg.text()).contains("***");
    }

    @Test
    void runCompletedMasksSecretInFinalMessage() {
        String finalMessage = "Result: password=super-secret-123";
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.runCompleted("run-1", 3, finalMessage);
        assertThat(msg.text()).doesNotContain("super-secret-123");
        assertThat(msg.text()).contains("***");
    }

    @Test
    void runFailedMasksSecretInReason() {
        String reason = "Error: Authorization: Bearer leaked-token-xyz";
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.runFailed("run-1", reason);
        assertThat(msg.text()).doesNotContain("leaked-token-xyz");
        assertThat(msg.text()).contains("***");
    }

    @Test
    void approvalPendingMasksSecretInArgs() {
        String args = "{\"client_secret\":\"cs-abcdef\",\"path\":\"/tmp\"}";
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.approvalPending("run-1", "apr-123", "write_file", args);
        assertThat(msg.text()).doesNotContain("cs-abcdef");
        assertThat(msg.text()).contains("***");
    }

    @Test
    void thinkingDoesNotContainSecrets() {
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.thinking("run-1");
        assertThat(msg.text()).isEqualTo("🤔 Thinking...");
    }

    @Test
    void sanitizeRunsBeforeTruncate() {
        String longSecret = "api_key=" + "s".repeat(500);
        ChatOpsOutboundMessage msg = ChatOpsOutboundMessage.runStarted("run-1", longSecret);
        assertThat(msg.text()).contains("***");
        assertThat(msg.text()).doesNotContain("s".repeat(10));
        assertThat(msg.text().length()).isLessThanOrEqualTo(120);
    }
}
