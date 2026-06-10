package com.tinyclaw.adapters.llm.springai;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.ToolCall;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Maps between TinyClaw domain {@link Message} and Spring AI message types.
 */
public final class SpringAiMessageMapper {

    private SpringAiMessageMapper() {
    }

    /**
     * Converts a list of domain messages to Spring AI messages.
     *
     * @param messages domain messages
     * @return Spring AI messages
     */
    public static List<org.springframework.ai.chat.messages.Message> toSpringAiMessages(List<Message> messages) {
        List<org.springframework.ai.chat.messages.Message> result = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            result.add(toSpringAiMessage(msg));
        }
        return result;
    }

    private static org.springframework.ai.chat.messages.Message toSpringAiMessage(Message msg) {
        return switch (msg.role()) {
            case SYSTEM -> new SystemMessage(msg.content());
            case USER -> {
                if (msg.toolCallId() != null && !msg.toolCallId().isBlank()) {
                    yield ToolResponseMessage.builder()
                        .responses(List.of(
                            new ToolResponseMessage.ToolResponse(msg.toolCallId(), null, msg.content())
                        ))
                        .build();
                }
                yield new UserMessage(msg.content());
            }
            case ASSISTANT -> {
                List<AssistantMessage.ToolCall> toolCalls = msg.toolCalls().stream()
                    .map(tc -> new AssistantMessage.ToolCall(tc.id(), "function", tc.name(), tc.argumentsJson()))
                    .toList();
                yield AssistantMessage.builder()
                    .content(msg.content())
                    .toolCalls(toolCalls)
                    .build();
            }
        };
    }

    /**
     * Extracts assistant content from a Spring AI {@link AssistantMessage}.
     *
     * @param assistantMessage the Spring AI assistant message
     * @return assistant content (never null)
     */
    public static String extractContent(AssistantMessage assistantMessage) {
        return assistantMessage.getText() != null ? assistantMessage.getText() : "";
    }

    /**
     * Extracts tool calls from a Spring AI {@link AssistantMessage}.
     *
     * @param assistantMessage the Spring AI assistant message
     * @return list of domain tool calls
     */
    public static List<ToolCall> extractToolCalls(AssistantMessage assistantMessage) {
        List<AssistantMessage.ToolCall> springToolCalls = assistantMessage.getToolCalls();
        if (springToolCalls == null || springToolCalls.isEmpty()) {
            return List.of();
        }
        return springToolCalls.stream()
            .map(tc -> ToolCall.of(tc.id(), tc.name(), tc.arguments()))
            .toList();
    }
}
