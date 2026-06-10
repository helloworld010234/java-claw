package com.tinyclaw.application.engine;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * Selects the working memory window that will be passed to the LLM.
 *
 * <p>Discards system messages, keeps the most recent messages up to a limit,
 * removes orphaned tool observations at the start of the window, and injects
 * a checkpoint user message when the first surviving message is not a user.</p>
 */
public class WorkingMemorySelector {

    public static final String CHECKPOINT_CONTENT =
        "[system checkpoint] Continue the previous task from the available context.";

    /**
     * Selects the working memory for the next LLM request.
     *
     * @param messages all session messages (may include system messages)
     * @param limit    maximum number of messages to return, or {@code <= 0} for unlimited
     * @return ordered working memory list, never null
     */
    public List<Message> select(List<Message> messages, int limit) {
        List<Message> nonSystem = messages.stream()
            .filter(m -> m.role() != Role.SYSTEM)
            .toList();

        if (nonSystem.isEmpty()) {
            return List.of();
        }

        int total = nonSystem.size();
        int take = (limit <= 0) ? total : Math.min(limit, total);
        List<Message> window = new ArrayList<>(nonSystem.subList(total - take, total));

        while (!window.isEmpty()) {
            Message first = window.get(0);
            if (first.role() == Role.USER && first.toolCallId() != null && !first.toolCallId().isBlank()) {
                window.remove(0);
            } else {
                break;
            }
        }

        if (!window.isEmpty() && window.get(0).role() != Role.USER) {
            window.add(0, Message.user(CHECKPOINT_CONTENT));
        }

        return List.copyOf(window);
    }
}
