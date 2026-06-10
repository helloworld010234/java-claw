package com.tinyclaw.application.engine;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Detects repeated failures of the same tool call and injects a reminder.
 *
 * <p>State is scoped to a single agent run. A successful
 * tool execution clears all failure counters.</p>
 */
public class ToolFailureReminder {

    private final Map<String, Integer> consecutiveFailures = new HashMap<>();

    /**
     * Records a tool result and returns a reminder message when the same
     * tool + arguments have failed three or more consecutive times.
     *
     * @param toolCall the tool call that was executed
     * @param result   the tool execution result
     * @return reminder message if the failure threshold is reached, otherwise empty
     */
    public Optional<Message> onToolResult(ToolCall toolCall, ToolResult result) {
        String fingerprint = fingerprint(toolCall);

        if (!result.error()) {
            consecutiveFailures.clear();
            return Optional.empty();
        }

        int count = consecutiveFailures.getOrDefault(fingerprint, 0) + 1;
        consecutiveFailures.put(fingerprint, count);

        if (count >= 3) {
            String content = String.format(
                "[SYSTEM REMINDER] You have failed %d times with the same arguments for '%s'. "
                    + "Stop repeating the same call. Change your strategy, verify assumptions with read_file or shell_command, or explain the blocker to the user.",
                count, toolCall.name()
            );
            return Optional.of(Message.user(content));
        }

        return Optional.empty();
    }

    private String fingerprint(ToolCall call) {
        return call.name() + "|" + call.argumentsJson();
    }
}
