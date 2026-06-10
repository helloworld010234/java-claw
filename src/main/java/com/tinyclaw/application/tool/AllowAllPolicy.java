package com.tinyclaw.application.tool;

import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.ports.tool.ToolExecutionDecision;
import com.tinyclaw.ports.tool.ToolExecutionPolicy;

/**
 * Default permissive policy that allows every tool call.
 */
public class AllowAllPolicy implements ToolExecutionPolicy {

    @Override
    public ToolExecutionDecision decide(ToolCall call) {
        return ToolExecutionDecision.allow();
    }
}
