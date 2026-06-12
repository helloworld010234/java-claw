package com.tinyclaw.application.chatops;

import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.ports.chatops.FakeChatOpsMessageSender;
import com.tinyclaw.ports.chatops.ChatOpsOutboundMessage;
import com.tinyclaw.ports.reporter.RunReportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatOpsReporterTest {

    private FakeChatOpsMessageSender sender;
    private ChatOpsReporter reporter;

    @BeforeEach
    void setUp() {
        sender = new FakeChatOpsMessageSender();
        reporter = new ChatOpsReporter(sender, "chat-1");
    }

    @Test
    void onThinkingStartedSendsThinkingMessage() {
        reporter.onThinkingStarted("run-1");
        assertThat(sender.hasMessageContaining("Thinking")).isTrue();
    }

    @Test
    void onToolCallSendsToolCallMessage() {
        reporter.onToolCall("run-1", ToolCall.of("tc-1", "shell_command", "{\"command\":\"echo hi\"}"));
        assertThat(sender.hasMessageContaining("shell_command")).isTrue();
        assertThat(sender.hasMessageContaining("echo hi")).isTrue();
    }

    @Test
    void onToolCallTruncatesLongArguments() {
        String longArgs = "x".repeat(500);
        reporter.onToolCall("run-1", ToolCall.of("tc-1", "shell_command", longArgs));
        var msg = sender.getMessages().get(0);
        assertThat(msg.text()).contains("... (truncated)");
        assertThat(msg.text().length()).isLessThan(longArgs.length() + 50);
    }

    @Test
    void onToolResultSendsResultMessage() {
        reporter.onToolResult("run-1", ToolResult.success("tc-1", "output here"));
        assertThat(sender.hasMessageContaining("output here")).isTrue();
    }

    @Test
    void onToolResultTruncatesLongOutput() {
        String longOutput = "o".repeat(1000);
        reporter.onToolResult("run-1", ToolResult.success("tc-1", longOutput));
        var msg = sender.getMessages().get(0);
        assertThat(msg.text()).contains("... (truncated)");
    }

    @Test
    void onRunCompletedSendsCompletedMessage() {
        reporter.onRunCompleted("run-1", new RunReportResult(true, "final answer", 3, null));
        assertThat(sender.hasMessageContaining("completed")).isTrue();
        assertThat(sender.hasMessageContaining("final answer")).isTrue();
    }

    @Test
    void onRunFailedSendsFailedMessage() {
        reporter.onRunFailed("run-1", "something broke");
        assertThat(sender.hasMessageContaining("Run failed")).isTrue();
        assertThat(sender.hasMessageContaining("something broke")).isTrue();
    }

    @Test
    void onUsageDoesNotSendMessage() {
        reporter.onUsage("run-1", "sess-1", new Usage(10, 20), "model-x");
        assertThat(sender.getMessages()).isEmpty();
    }

    @Test
    void onAssistantMessageDoesNotSendMessage() {
        reporter.onAssistantMessage("run-1", "assistant content");
        assertThat(sender.getMessages()).isEmpty();
    }

    @Test
    void notifyApprovalPendingSendsApprovalMessage() {
        reporter.notifyApprovalPending("run-1", "apr-123", "write_file", "{\"path\":\"x\"}");
        assertThat(sender.hasMessageContaining("Approval required")).isTrue();
        assertThat(sender.hasMessageContaining("write_file")).isTrue();
    }

    @Test
    void outboundMessageDoesNotContainFullSensitiveArgs() {
        String args = "{\"api_key\":\"sk-secret123\",\"command\":\"curl -H Authorization: Bearer token\"}".repeat(5);
        reporter.onToolCall("run-1", ToolCall.of("tc-1", "shell_command", args));
        var msg = sender.getMessages().get(0);
        // The message should contain a truncated preview, not the full args
        assertThat(msg.text()).contains("... (truncated)");
        assertThat(msg.text().length()).isLessThan(args.length());
    }

    @Test
    void toolCallMasksApiKey() {
        String args = "{\"api_key\":\"sk-abc123secret\",\"command\":\"echo hi\"}";
        reporter.onToolCall("run-1", ToolCall.of("tc-1", "shell_command", args));
        var msg = sender.getMessages().get(0);
        assertThat(msg.text()).doesNotContain("sk-abc123secret");
        assertThat(msg.text()).contains("***");
    }

    @Test
    void toolCallMasksBearerToken() {
        String args = "{\"header\":\"Authorization: Bearer secret-token-xyz\"}";
        reporter.onToolCall("run-1", ToolCall.of("tc-1", "shell_command", args));
        var msg = sender.getMessages().get(0);
        assertThat(msg.text()).doesNotContain("secret-token-xyz");
        assertThat(msg.text()).contains("***");
    }

    @Test
    void toolResultMasksSecretInOutput() {
        String output = "Response: {\"access_token\":\"tok-12345\",\"status\":\"ok\"}";
        reporter.onToolResult("run-1", ToolResult.success("tc-1", output));
        var msg = sender.getMessages().get(0);
        assertThat(msg.text()).doesNotContain("tok-12345");
        assertThat(msg.text()).contains("***");
    }

    @Test
    void runCompletedMasksSecretInFinalMessage() {
        String finalMessage = "Here is your api_key=sk-live-99999 result";
        reporter.onRunCompleted("run-1", new RunReportResult(true, finalMessage, 2, null));
        var msg = sender.getMessages().get(0);
        assertThat(msg.text()).doesNotContain("sk-live-99999");
        assertThat(msg.text()).contains("***");
    }

    @Test
    void runFailedMasksSecretInReason() {
        String reason = "Failed because password=super-secret-123";
        reporter.onRunFailed("run-1", reason);
        var msg = sender.getMessages().get(0);
        assertThat(msg.text()).doesNotContain("super-secret-123");
        assertThat(msg.text()).contains("***");
    }

    @Test
    void approvalPendingMasksSecretInArgs() {
        String args = "{\"client_secret\":\"cs-abcdef\",\"path\":\"/tmp\"}";
        reporter.notifyApprovalPending("run-1", "apr-123", "write_file", args);
        var msg = sender.getMessages().get(0);
        assertThat(msg.text()).doesNotContain("cs-abcdef");
        assertThat(msg.text()).contains("***");
    }
}
