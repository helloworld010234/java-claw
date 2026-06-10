package com.tinyclaw.adapters.session;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.ports.session.SessionService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session storage for testing and local development.
 *
 * <p>Not thread-safe for concurrent modifications to the same session,
 * but safe for independent sessions.</p>
 */
public class InMemorySessionService implements SessionService {

    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();

    @Override
    public void appendMessage(String sessionId, Message message) {
        store.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()))
             .add(message);
    }

    @Override
    public List<Message> getWorkingMemory(String sessionId) {
        List<Message> messages = store.get(sessionId);
        if (messages == null) {
            return List.of();
        }
        return List.copyOf(messages);
    }

    @Override
    public void replaceMessages(String sessionId, List<Message> messages) {
        List<Message> copy = messages == null ? List.of() : List.copyOf(messages);
        store.put(sessionId, Collections.synchronizedList(new ArrayList<>(copy)));
    }

    /**
     * Clear all stored messages.
     */
    public void clear() {
        store.clear();
    }
}
