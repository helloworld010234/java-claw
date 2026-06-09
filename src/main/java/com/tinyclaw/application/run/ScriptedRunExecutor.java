package com.tinyclaw.application.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.tool.ToolExecutionContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes a scripted run plan by translating each step into a {@link ToolCall}
 * and delegating to the {@link ToolRegistry}.
 */
public class ScriptedRunExecutor {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public ScriptedRunExecutor(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = DomainGuards.requireNonNull(toolRegistry, "toolRegistry");
        this.objectMapper = DomainGuards.requireNonNull(objectMapper, "objectMapper");
    }

    public ScriptedRunResult execute(ScriptedRunPlan plan, ToolExecutionContext context) {
        List<ScriptedRunStepResult> results = new ArrayList<>();

        for (ScriptedRunStep step : plan.steps()) {
            String argsJson;
            try {
                argsJson = objectMapper.writeValueAsString(step.args());
            } catch (JsonProcessingException e) {
                results.add(new ScriptedRunStepResult(
                    step.id(), step.tool(), true,
                    "Failed to serialize args: " + e.getMessage()
                ));
                if (plan.shouldStopOnError()) {
                    break;
                }
                continue;
            }

            ToolCall call = ToolCall.of(step.id(), step.tool(), argsJson);
            ToolResult result = toolRegistry.execute(call, context);
            results.add(new ScriptedRunStepResult(
                step.id(), step.tool(), result.error(), result.output()
            ));

            if (result.error() && plan.shouldStopOnError()) {
                break;
            }
        }

        boolean success = results.stream().noneMatch(ScriptedRunStepResult::error);
        return new ScriptedRunResult(success, List.copyOf(results));
    }
}
