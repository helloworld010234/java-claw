package com.tinyclaw.ports.persistence;

import com.tinyclaw.application.persistence.AgentMessageDto;
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
}
