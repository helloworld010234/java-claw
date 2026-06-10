package com.tinyclaw.application.persistence;

import com.tinyclaw.domain.message.Role;

import java.time.Instant;

/**
 * DTO for an audited agent message.
 */
public record AgentMessageDto(
    String id,
    String runId,
    String sessionId,
    Role role,
    String content,
    String toolCallId,
    Instant createdAt
) {}
