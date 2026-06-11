package com.tinyclaw.ports.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.message.ToolCall;

import java.time.Instant;
import java.util.List;

/**
 * DTO for an audited agent message.
 *
 * <p>Carries enough data to reconstruct the domain {@link Message} including
 * tool calls and tool-call identifiers.</p>
 */
public record AgentMessageDto(
    String id,
    String runId,
    String sessionId,
    Role role,
    String content,
    String toolCallsJson,
    String toolCallId,
    int sequenceNumber,
    Instant createdAt
) {

    /**
     * Reconstructs a domain {@link Message} from this DTO.
     *
     * @param objectMapper mapper used to deserialize the {@code tool_calls} JSON blob
     * @return a domain message value object
     */
    public Message toMessage(ObjectMapper objectMapper) {
        if (role == null) {
            throw new IllegalStateException("Role must not be null for message id=" + id);
        }
        return switch (role) {
            case SYSTEM -> Message.system(content != null ? content : "");
            case USER -> {
                if (toolCallId != null && !toolCallId.isBlank()) {
                    yield Message.toolObservation(toolCallId, content != null ? content : "");
                }
                yield Message.user(content != null ? content : "");
            }
            case ASSISTANT -> {
                List<ToolCall> toolCalls = parseToolCalls(objectMapper);
                if (!toolCalls.isEmpty()) {
                    yield Message.assistantWithToolCalls(content != null ? content : "", toolCalls);
                }
                yield Message.assistant(content != null ? content : "");
            }
        };
    }

    private List<ToolCall> parseToolCalls(ObjectMapper objectMapper) {
        if (toolCallsJson == null || toolCallsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(toolCallsJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize tool_calls for message id=" + id, e);
        }
    }
}
