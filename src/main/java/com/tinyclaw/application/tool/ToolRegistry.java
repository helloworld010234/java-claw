package com.tinyclaw.application.tool;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.common.TinyClawDomainException;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.tool.AgentTool;
import com.tinyclaw.ports.tool.ToolExecutionContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Application-level registry for agent tools.
 */
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> tools) {
        DomainGuards.requireNonNull(tools, "tools");
        this.tools = tools.stream()
            .collect(Collectors.toUnmodifiableMap(
                this::toolName,
                tool -> tool,
                this::rejectDuplicate
            ));
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        DomainGuards.requireNonNull(call, "call");
        DomainGuards.requireNonNull(context, "context");

        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.failure(call.id(), "Unknown tool: " + call.name());
        }

        try {
            return tool.execute(call, context);
        } catch (Exception e) {
            return ToolResult.failure(call.id(), "Tool execution failed: " + e.getMessage());
        }
    }

    private String toolName(AgentTool tool) {
        DomainGuards.requireNonNull(tool, "tool");
        String name = tool.name();
        DomainGuards.requireNonBlank(name, "tool name");
        return name;
    }

    private AgentTool rejectDuplicate(AgentTool first, AgentTool second) {
        throw new TinyClawDomainException("Duplicate tool name: " + first.name());
    }
}
