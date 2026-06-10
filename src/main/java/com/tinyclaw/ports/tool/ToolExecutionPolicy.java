package com.tinyclaw.ports.tool;

import com.tinyclaw.domain.message.ToolCall;

/**
 * Policy that intercepts tool execution before the actual tool is invoked.
 *
 * <p>Multiple policies can be registered with the {@link ToolRegistry}.
 * They are evaluated in order; the first rejection stops the chain.</p>
 */
public interface ToolExecutionPolicy {

    /**
     * Decide whether the given tool call should be allowed to execute.
     *
     * @param call the tool call being evaluated
     * @return a decision indicating allow or deny with a reason
     */
    ToolExecutionDecision decide(ToolCall call);
}
