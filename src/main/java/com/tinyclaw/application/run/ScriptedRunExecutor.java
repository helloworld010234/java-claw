package com.tinyclaw.application.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.ports.persistence.ToolExecutionRecord;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.tool.ToolExecutionContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        return execute(plan, context, null, null, null);
    }

    public ScriptedRunResult execute(ScriptedRunPlan plan, ToolExecutionContext context,
                                      String runId, String sessionId, ToolExecutionRepositoryPort toolRepo) {
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
            Instant startedAt = Instant.now();
            ToolResult result = toolRegistry.execute(call, context);
            Instant completedAt = Instant.now();

            results.add(new ScriptedRunStepResult(
                step.id(), step.tool(), result.error(), result.output()
            ));

            if (toolRepo != null && runId != null) {
                ToolExecutionRecord record = new ToolExecutionRecord(
                    UUID.randomUUID().toString(),
                    runId,
                    sessionId,
                    step.id(),
                    step.tool(),
                    argsJson,
                    result.output(),
                    result.error(),
                    startedAt,
                    completedAt
                );
                toolRepo.append(runId, record);
            }

            if (result.error() && plan.shouldStopOnError()) {
                break;
            }
        }

        boolean success = results.stream().noneMatch(ScriptedRunStepResult::error);
        return new ScriptedRunResult(success, List.copyOf(results));
    }
}
