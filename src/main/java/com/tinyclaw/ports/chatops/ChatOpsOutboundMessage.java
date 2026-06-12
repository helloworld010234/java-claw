package com.tinyclaw.ports.chatops;

import java.time.Instant;

/**
 * Structured outbound message for ChatOps channels.
 *
 * <p>Deliberately minimal: only text, run reference, and timestamp.
 * No platform-specific card or button concepts.</p>
 *
 * @param text      human-readable message content (short, truncated if needed)
 * @param runId     the run identifier for traceability (may be a short prefix)
 * @param timestamp when the event occurred
 * @param type      the message type for optional formatting hints
 */
public record ChatOpsOutboundMessage(String text, String runId, Instant timestamp, Type type) {

    public enum Type {
        RUN_STARTED,
        TOOL_CALL,
        APPROVAL_PENDING,
        TOOL_RESULT,
        RUN_COMPLETED,
        RUN_FAILED,
        THINKING
    }

    public ChatOpsOutboundMessage {
        if (text == null) {
            text = "";
        }
        if (runId == null) {
            runId = "";
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (type == null) {
            type = Type.TOOL_RESULT;
        }
    }

    public static ChatOpsOutboundMessage runStarted(String runId, String promptPreview) {
        String sanitized = ChatOpsSanitizer.sanitize(promptPreview);
        String text = "▶️ Run started" + (sanitized != null && !sanitized.isBlank()
            ? ". Prompt: " + truncate(sanitized, 80)
            : "");
        return new ChatOpsOutboundMessage(text, runId, Instant.now(), Type.RUN_STARTED);
    }

    public static ChatOpsOutboundMessage toolCall(String runId, String toolName, String argsPreview) {
        String sanitized = ChatOpsSanitizer.sanitize(argsPreview);
        String text = "🛠️ Tool call: " + toolName
            + (sanitized != null && !sanitized.isBlank()
                ? " | args: " + truncate(sanitized, 120)
                : "");
        return new ChatOpsOutboundMessage(text, runId, Instant.now(), Type.TOOL_CALL);
    }

    public static ChatOpsOutboundMessage approvalPending(String runId, String approvalId, String toolName, String argsPreview) {
        String sanitized = ChatOpsSanitizer.sanitize(argsPreview);
        String text = "⏸️ Approval required | tool: " + toolName
            + " | approval: " + shortId(approvalId)
            + (sanitized != null && !sanitized.isBlank()
                ? " | args: " + truncate(sanitized, 120)
                : "");
        return new ChatOpsOutboundMessage(text, runId, Instant.now(), Type.APPROVAL_PENDING);
    }

    public static ChatOpsOutboundMessage toolResult(String runId, String toolName, boolean error, String outputPreview) {
        String sanitized = ChatOpsSanitizer.sanitize(outputPreview);
        String prefix = error ? "❌ Tool failed" : "✅ Tool ok";
        String text = prefix + " | tool: " + toolName
            + " | output: " + truncate(sanitized, 200);
        return new ChatOpsOutboundMessage(text, runId, Instant.now(), Type.TOOL_RESULT);
    }

    public static ChatOpsOutboundMessage runCompleted(String runId, int turns, String finalMessagePreview) {
        String sanitized = ChatOpsSanitizer.sanitize(finalMessagePreview);
        String text = "✅ Run completed in " + turns + " turn(s)"
            + (sanitized != null && !sanitized.isBlank()
                ? " | last: " + truncate(sanitized, 120)
                : "");
        return new ChatOpsOutboundMessage(text, runId, Instant.now(), Type.RUN_COMPLETED);
    }

    public static ChatOpsOutboundMessage runFailed(String runId, String reason) {
        String sanitized = ChatOpsSanitizer.sanitize(reason);
        String text = "❌ Run failed | " + truncate(sanitized, 200);
        return new ChatOpsOutboundMessage(text, runId, Instant.now(), Type.RUN_FAILED);
    }

    public static ChatOpsOutboundMessage thinking(String runId) {
        return new ChatOpsOutboundMessage("🤔 Thinking...", runId, Instant.now(), Type.THINKING);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= maxLen) {
            return t;
        }
        return t.substring(0, maxLen) + "... (truncated)";
    }

    private static String shortId(String id) {
        if (id == null || id.length() <= 8) {
            return id != null ? id : "";
        }
        return id.substring(0, 8) + "...";
    }
}
