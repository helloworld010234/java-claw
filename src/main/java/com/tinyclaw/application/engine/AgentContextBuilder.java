package com.tinyclaw.application.engine;

import com.tinyclaw.domain.message.Message;

import java.util.List;

/**
 * Builds the full LLM request context for each turn.
 *
 * <p>Composes the system prompt, selects the working memory window, and
 * applies deterministic compaction. Keeps context construction details out
 * of {@link AgentEngine}.</p>
 */
public class AgentContextBuilder {

    private final PromptComposer promptComposer;
    private final WorkingMemorySelector workingMemorySelector;
    private final ContextCompactor contextCompactor;

    public AgentContextBuilder(PromptComposer promptComposer,
                               WorkingMemorySelector workingMemorySelector,
                               ContextCompactor contextCompactor) {
        this.promptComposer = promptComposer;
        this.workingMemorySelector = workingMemorySelector;
        this.contextCompactor = contextCompactor;
    }

    /**
     * Builds the message context for the next LLM request.
     *
     * @param workspaceRoot       the workspace root used for the system prompt
     * @param sessionMessages     all messages currently in the session
     * @param workingMemoryLimit  maximum number of recent messages to include
     * @return ordered list of messages starting with the system prompt
     */
    public List<Message> build(String workspaceRoot, List<Message> sessionMessages, int workingMemoryLimit) {
        Message systemMessage = promptComposer.compose(workspaceRoot);
        List<Message> workingMemory = workingMemorySelector.select(sessionMessages, workingMemoryLimit);
        return contextCompactor.compact(systemMessage, workingMemory);
    }
}
