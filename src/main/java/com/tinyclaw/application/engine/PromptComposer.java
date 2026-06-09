package com.tinyclaw.application.engine;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.Message;

/**
 * Constructs the system prompt for an agent run.
 *
 * <p>Minimal version: basic identity and workspace context.</p>
 */
public class PromptComposer {

    public Message compose(String workspaceRoot) {
        DomainGuards.requireNonBlank(workspaceRoot, "workspaceRoot");
        String content = "You are TinyClaw, a helpful AI assistant.\n"
            + "You have access to file tools in workspace: " + workspaceRoot + "\n"
            + "Use tools to complete user requests.";
        return Message.system(content);
    }
}
