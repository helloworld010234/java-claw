package com.tinyclaw.ports.session;

import com.tinyclaw.domain.message.Message;

import java.util.List;

/**
 * Port for persisting and retrieving session messages.
 *
 * <p>Implementations may be in-memory (testing) or database-backed (production).</p>
 */
public interface SessionService {

    /**
     * Append a message to the session's message history.
     *
     * @param sessionId the session identifier
     * @param message   the message to append
     */
    void appendMessage(String sessionId, Message message);

    /**
     * Retrieve the working memory (message history) for a session.
     *
     * @param sessionId the session identifier
     * @return ordered list of messages, never null
     */
    List<Message> getWorkingMemory(String sessionId);
}
