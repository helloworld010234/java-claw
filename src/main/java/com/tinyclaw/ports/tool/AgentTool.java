package com.tinyclaw.ports.tool;

import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;

/**
 * Port for a tool that can be exposed to an agent run.
 */
public interface AgentTool {

    String name();

    ToolDefinition definition();

    ToolResult execute(ToolCall call, ToolExecutionContext context);
}
