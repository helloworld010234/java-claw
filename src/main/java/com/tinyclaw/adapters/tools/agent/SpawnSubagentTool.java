package com.tinyclaw.adapters.tools.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.ports.engine.SubagentRunner;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.tool.AgentTool;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 派出子 Agent 进行深度探索的工具。
 *
 * <p>当主 Agent 需要阅读大量代码、跨文件查找逻辑时调用此工具。
 * 子 Agent 在受限环境中运行，只能访问只读工具，探索完毕后返回精炼摘要报告。</p>
 */
@Component
public class SpawnSubagentTool implements AgentTool {

    public static final String NAME = "spawn_subagent";
    private static final String DESCRIPTION = "派出一个专门用于深度探索（Exploration）的子智能体。当你需要阅读大量代码、跨文件查找逻辑时请调用此工具。它在探索完毕后，会给你返回一份极度精炼的摘要报告。";
    private static final String INPUT_SCHEMA_JSON = """
        {
          "type": "object",
          "properties": {
            "task_prompt": {
              "type": "string",
              "description": "给子智能体下达的明确探索指令。"
            }
          },
          "required": ["task_prompt"]
        }
        """;

    private final SubagentRunner subagentRunner;
    private final ObjectMapper objectMapper;
    private final com.tinyclaw.application.tool.ToolRegistry readOnlyRegistry;
    private final Reporter reporter;

    public SpawnSubagentTool(@Lazy SubagentRunner subagentRunner,
                             ObjectMapper objectMapper,
                             @Qualifier("readOnlyToolRegistry") ToolRegistry readOnlyRegistry,
                             Reporter reporter) {
        this.subagentRunner = DomainGuards.requireNonNull(subagentRunner, "subagentRunner");
        this.objectMapper = DomainGuards.requireNonNull(objectMapper, "objectMapper");
        this.readOnlyRegistry = DomainGuards.requireNonNull(readOnlyRegistry, "readOnlyRegistry");
        this.reporter = reporter;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(NAME, DESCRIPTION, INPUT_SCHEMA_JSON);
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext context) {
        String taskPrompt;
        try {
            JsonNode root = objectMapper.readTree(call.argumentsJson());
            JsonNode promptNode = root.get("task_prompt");
            if (promptNode == null || !promptNode.isTextual()) {
                return ToolResult.failure(call.id(), "Missing or invalid 'task_prompt' argument");
            }
            taskPrompt = promptNode.asText();
        } catch (IOException e) {
            return ToolResult.failure(call.id(), "Invalid arguments JSON: " + e.getMessage());
        }

        if (taskPrompt.isBlank()) {
            return ToolResult.failure(call.id(), "task_prompt must not be blank");
        }

        String report = subagentRunner.runSub(taskPrompt, readOnlyRegistry, reporter, context.workspaceRoot().toString());

        return ToolResult.success(call.id(), "【子智能体探索报告】:\n" + report);
    }
}
