package com.tinyclaw.application.chatops;

import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.ports.chatops.ChatOpsMessageSender;
import com.tinyclaw.ports.chatops.ChatOpsOutboundMessage;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.reporter.RunReportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reporter implementation that forwards agent lifecycle events to a ChatOps channel.
 *
 * <p>All outbound messages are short, readable, and traceable. Sensitive data
 * (full arguments, API keys, headers) is never included. Long tool outputs are
 * truncated.</p>
 */
public class ChatOpsReporter implements Reporter {

    private static final Logger log = LoggerFactory.getLogger(ChatOpsReporter.class);
    private static final int MAX_OUTPUT_LEN = 400;
    private static final int MAX_ARGS_LEN = 200;

    private final ChatOpsMessageSender sender;
    private final String chatId;

    public ChatOpsReporter(ChatOpsMessageSender sender, String chatId) {
        this.sender = sender;
        this.chatId = chatId;
    }

    @Override
    public void onThinkingStarted(String runId) {
        sender.sendMessage(chatId, ChatOpsOutboundMessage.thinking(runId));
    }

    @Override
    public void onAssistantMessage(String runId, String content) {
        // Intentionally minimal: do not echo every assistant message to avoid spam.
        // The final message is included in runCompleted / runFailed.
    }

    @Override
    public void onToolCall(String runId, ToolCall toolCall) {
        String args = toolCall.argumentsJson();
        if (args != null && args.length() > MAX_ARGS_LEN) {
            args = args.substring(0, MAX_ARGS_LEN) + "... (truncated)";
        }
        sender.sendMessage(chatId, ChatOpsOutboundMessage.toolCall(runId, toolCall.name(), args));
    }

    @Override
    public void onToolResult(String runId, ToolResult toolResult) {
        String output = toolResult.output();
        if (output != null && output.length() > MAX_OUTPUT_LEN) {
            output = output.substring(0, MAX_OUTPUT_LEN) + "... (truncated)";
        }
        sender.sendMessage(chatId, ChatOpsOutboundMessage.toolResult(runId, "tool", toolResult.error(), output));
    }

    @Override
    public void onRunCompleted(String runId, RunReportResult result) {
        sender.sendMessage(chatId, ChatOpsOutboundMessage.runCompleted(runId, result.turnCount(), result.finalMessage()));
    }

    @Override
    public void onRunFailed(String runId, String reason) {
        sender.sendMessage(chatId, ChatOpsOutboundMessage.runFailed(runId, reason));
    }

    @Override
    public void onUsage(String runId, String sessionId, Usage usage, String model) {
        // Usage is not sent to chat to keep messages short.
    }

    /**
     * Send a custom approval-pending notification.
     * Called by the approval service or event handler when a new approval is created.
     */
    public void notifyApprovalPending(String runId, String approvalId, String toolName, String argsPreview) {
        sender.sendMessage(chatId, ChatOpsOutboundMessage.approvalPending(runId, approvalId, toolName, argsPreview));
    }
}
