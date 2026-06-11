package com.tinyclaw.ports.persistence;

import com.tinyclaw.domain.message.Message;

import java.util.List;

/**
 * Port for persisting and retrieving agent messages for audit.
 */
public interface MessageRepositoryPort {

    /**
     * Append a message to the audit log for a run.
     */
    void append(String runId, String sessionId, Message message);

    /**
     * Find all messages for a run, ordered by creation time.
     */
    List<AgentMessageDto> findByRunId(String runId);

    /**
     * Find messages for a session, ordered by sequence number and creation time.
     *
     * <p>System messages are excluded. If {@code limit} is {@code <= 0}, all
     * matching messages are returned.</p>
     *
     * @param sessionId the session identifier
     * @param limit     maximum number of messages to return, or {@code <= 0} for unlimited
     * @return ordered list of DTOs convertible back to {@link Message}
     */
    List<AgentMessageDto> findBySessionId(String sessionId, int limit);
}
