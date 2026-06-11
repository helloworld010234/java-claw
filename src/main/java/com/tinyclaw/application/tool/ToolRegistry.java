package com.tinyclaw.application.tool;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.common.TinyClawDomainException;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.observability.AgentMetricsPort;
import com.tinyclaw.ports.tool.AgentTool;
import com.tinyclaw.ports.tool.ToolCatalog;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import com.tinyclaw.ports.tool.ToolExecutionDecision;
import com.tinyclaw.ports.tool.ToolExecutionDecisionType;
import com.tinyclaw.ports.tool.ToolExecutionPolicy;

import com.tinyclaw.domain.message.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Application-level registry for agent tools.
 */
public class ToolRegistry implements ToolCatalog {

    private final Map<String, AgentTool> tools;
    private final List<ToolExecutionPolicy> policies;
    private final AgentMetricsPort agentMetrics;

    public ToolRegistry(List<AgentTool> tools) {
        this(tools, List.of(), null);
    }

    public ToolRegistry(List<AgentTool> tools, List<ToolExecutionPolicy> policies) {
        this(tools, policies, null);
    }

    public ToolRegistry(List<AgentTool> tools, List<ToolExecutionPolicy> policies, AgentMetricsPort agentMetrics) {
        DomainGuards.requireNonNull(tools, "tools");
        DomainGuards.requireNonNull(policies, "policies");
        this.tools = tools.stream()
            .collect(Collectors.toUnmodifiableMap(
                this::toolName,
                tool -> tool,
                this::rejectDuplicate
            ));
        this.policies = List.copyOf(policies);
        this.agentMetrics = agentMetrics;
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Returns definitions for all registered tools.
     */
    public List<ToolDefinition> availableTools() {
        return tools.values().stream()
            .map(AgentTool::definition)
            .toList();
    }

    public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        DomainGuards.requireNonNull(call, "call");
        DomainGuards.requireNonNull(context, "context");

        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            if (agentMetrics != null) {
                agentMetrics.recordToolExecution(call.name(), false);
            }
            return ToolResult.failure(call.id(), "Unknown tool: " + call.name());
        }

        // Evaluate policies in order
        for (ToolExecutionPolicy policy : policies) {
            ToolExecutionDecision decision = policy.decide(call, context);
            if (decision.type() == ToolExecutionDecisionType.DENY) {
                if (agentMetrics != null) {
                    agentMetrics.recordToolExecution(call.name(), false);
                }
                return ToolResult.failure(call.id(), decision.reason());
            }
            if (decision.type() == ToolExecutionDecisionType.REQUIRE_APPROVAL) {
                if (agentMetrics != null) {
                    agentMetrics.recordToolExecution(call.name(), false);
                }
                return ToolResult.failure(call.id(), decision.reason());
            }
        }

        try {
            ToolResult result = tool.execute(call, context);
            if (agentMetrics != null) {
                agentMetrics.recordToolExecution(call.name(), !result.error());
            }
            return result;
        } catch (Exception e) {
            if (agentMetrics != null) {
                agentMetrics.recordToolExecution(call.name(), false);
            }
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
