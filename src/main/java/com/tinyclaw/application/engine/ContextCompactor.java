package com.tinyclaw.application.engine;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.message.ToolCall;

import java.util.ArrayList;
import java.util.List;

/**
 * Compacts a message context when it exceeds a character threshold.
 *
 * <p>Preserves the system message, protects the most recent messages, and
 * replaces older long content with deterministic placeholders. Original
 * messages are never mutated.</p>
 */
public class ContextCompactor {

    public static final int DEFAULT_MAX_CHARS = 20000;
    public static final int DEFAULT_RETAIN_LAST_MSGS = 6;
    public static final int EARLY_TOOL_TRUNCATE_THRESHOLD = 200;
    public static final int RECENT_TOOL_TRUNCATE_THRESHOLD = 1000;
    public static final int RECENT_TOOL_KEEP_CHARS = 500;
    public static final int ASSISTANT_COLLAPSE_THRESHOLD = 200;

    private final int maxChars;
    private final int retainLastMsgs;

    public ContextCompactor() {
        this(DEFAULT_MAX_CHARS, DEFAULT_RETAIN_LAST_MSGS);
    }

    public ContextCompactor(int maxChars, int retainLastMsgs) {
        this.maxChars = maxChars;
        this.retainLastMsgs = retainLastMsgs;
    }

    /**
     * Compacts the context if it exceeds the configured threshold.
     *
     * @param systemMessage  the system prompt message (always preserved)
     * @param workingMemory  the working memory selected for this turn
     * @return a new list containing the compacted context
     */
    public List<Message> compact(Message systemMessage, List<Message> workingMemory) {
        List<Message> combined = new ArrayList<>(workingMemory.size() + 1);
        combined.add(systemMessage);
        combined.addAll(workingMemory);

        if (estimateLength(combined) <= maxChars) {
            return List.copyOf(combined);
        }

        int protectStartIndex = Math.max(0, combined.size() - retainLastMsgs);
        List<Message> compacted = new ArrayList<>(combined.size());

        for (int i = 0; i < combined.size(); i++) {
            Message msg = combined.get(i);
            if (msg.role() == Role.SYSTEM) {
                compacted.add(msg);
                continue;
            }

            boolean inProtectedZone = i >= protectStartIndex;
            compacted.add(compactMessage(msg, inProtectedZone));
        }

        return List.copyOf(compacted);
    }

    private Message compactMessage(Message msg, boolean inProtectedZone) {
        if (msg.role() == Role.USER && msg.toolCallId() != null && !msg.toolCallId().isBlank()) {
            return compactToolObservation(msg, inProtectedZone);
        }

        if (msg.role() == Role.ASSISTANT) {
            return compactAssistantMessage(msg, inProtectedZone);
        }

        return msg;
    }

    private Message compactToolObservation(Message msg, boolean inProtectedZone) {
        String content = msg.content();

        if (!inProtectedZone && content.length() > EARLY_TOOL_TRUNCATE_THRESHOLD) {
            String replacement = "...[early tool output truncated; original length: " + content.length() + " chars]...";
            return Message.toolObservation(msg.toolCallId(), replacement);
        }

        if (inProtectedZone && content.length() > RECENT_TOOL_TRUNCATE_THRESHOLD) {
            String head = content.substring(0, RECENT_TOOL_KEEP_CHARS);
            String tail = content.substring(content.length() - RECENT_TOOL_KEEP_CHARS);
            int omitted = content.length() - RECENT_TOOL_TRUNCATE_THRESHOLD;
            String replacement = head + "\n\n...[middle " + omitted + " chars truncated]...\n\n" + tail;
            return Message.toolObservation(msg.toolCallId(), replacement);
        }

        return msg;
    }

    private Message compactAssistantMessage(Message msg, boolean inProtectedZone) {
        String content = msg.content();
        boolean hasToolCalls = !msg.toolCalls().isEmpty();

        if (!inProtectedZone && content.length() > ASSISTANT_COLLAPSE_THRESHOLD) {
            String replacement = "...[early assistant reasoning collapsed]...";
            if (hasToolCalls) {
                return Message.assistantWithToolCalls(replacement, msg.toolCalls());
            }
            return Message.assistant(replacement);
        }

        return msg;
    }

    private int estimateLength(List<Message> messages) {
        int length = 0;
        for (Message msg : messages) {
            String content = msg.content();
            length += content != null ? content.length() : 0;
            for (ToolCall tc : msg.toolCalls()) {
                length += tc.name() != null ? tc.name().length() : 0;
                length += tc.argumentsJson() != null ? tc.argumentsJson().length() : 0;
            }
        }
        return length;
    }
}
