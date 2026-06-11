package com.tinyclaw.ports.tool;

import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;

import java.util.List;
import java.util.Optional;

/**
 * Port abstraction for tool catalog and execution.
 *
 * <p>Decouples the {@code ports} layer from the {@code application.tool.ToolRegistry}
 * implementation. Any component that needs to list available tools or execute a
 * tool call can depend on this interface instead of the concrete registry.</p>
 */
public interface ToolCatalog {

    /**
     * Returns definitions for all available tools.
     */
    List<ToolDefinition> availableTools();

    /**
     * Execute a tool call.
     *
     * @param call    the tool call to execute
     * @param context the execution context
     * @return the tool result
     */
    ToolResult execute(ToolCall call, ToolExecutionContext context);

    /**
     * Find a tool by name.
     *
     * @param name the tool name
     * @return the tool if found
     */
    Optional<AgentTool> find(String name);
}
