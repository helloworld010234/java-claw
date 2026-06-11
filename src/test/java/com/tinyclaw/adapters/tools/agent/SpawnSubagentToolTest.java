package com.tinyclaw.adapters.tools.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.engine.SubagentRunner;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SpawnSubagentToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SubagentRunner subagentRunner = mock(SubagentRunner.class);
    private final ToolRegistry readOnlyRegistry = mock(ToolRegistry.class);
    private final Reporter reporter = mock(Reporter.class);

    private SpawnSubagentTool createTool() {
        return new SpawnSubagentTool(subagentRunner, objectMapper, readOnlyRegistry, reporter);
    }

    @Test
    void nameIsSpawnSubagent() {
        SpawnSubagentTool tool = createTool();
        assertThat(tool.name()).isEqualTo("spawn_subagent");
    }

    @Test
    void definitionHasCorrectSchema() {
        SpawnSubagentTool tool = createTool();
        assertThat(tool.definition().name()).isEqualTo("spawn_subagent");
        assertThat(tool.definition().description()).contains("深度探索");
        assertThat(tool.definition().inputSchemaJson()).contains("task_prompt");
    }

    @Test
    void executeWithValidTaskPromptCallsRunner() {
        when(subagentRunner.runSub(any(), any(), any(), any()))
            .thenReturn("探索完成，发现关键线索。");

        SpawnSubagentTool tool = createTool();
        ToolCall call = ToolCall.of("c1", "spawn_subagent",
            "{\"task_prompt\":\"查找所有包含TODO的代码文件\"}");
        ToolExecutionContext context = new ToolExecutionContext(tempDir);

        ToolResult result = tool.execute(call, context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains("【子智能体探索报告】");
        assertThat(result.output()).contains("探索完成，发现关键线索。");
        verify(subagentRunner).runSub(
            eq("查找所有包含TODO的代码文件"),
            eq(readOnlyRegistry),
            eq(reporter),
            eq(tempDir.toString())
        );
    }

    @Test
    void executeWithMissingTaskPromptReturnsFailure() {
        SpawnSubagentTool tool = createTool();
        ToolCall call = ToolCall.of("c1", "spawn_subagent", "{}");
        ToolExecutionContext context = new ToolExecutionContext(tempDir);

        ToolResult result = tool.execute(call, context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Missing or invalid 'task_prompt'");
        verifyNoInteractions(subagentRunner);
    }

    @Test
    void executeWithBlankTaskPromptReturnsFailure() {
        SpawnSubagentTool tool = createTool();
        ToolCall call = ToolCall.of("c1", "spawn_subagent",
            "{\"task_prompt\":\"     \"}");
        ToolExecutionContext context = new ToolExecutionContext(tempDir);

        ToolResult result = tool.execute(call, context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("task_prompt must not be blank");
        verifyNoInteractions(subagentRunner);
    }

    @Test
    void executeWithInvalidJsonReturnsFailure() {
        SpawnSubagentTool tool = createTool();
        ToolCall call = ToolCall.of("c1", "spawn_subagent", "not-json");
        ToolExecutionContext context = new ToolExecutionContext(tempDir);

        ToolResult result = tool.execute(call, context);

        assertThat(result.error()).isTrue();
        assertThat(result.output()).contains("Invalid arguments JSON");
        verifyNoInteractions(subagentRunner);
    }

    @Test
    void executeWithNullReporterStillCallsRunner() {
        SpawnSubagentTool tool = new SpawnSubagentTool(
            subagentRunner, objectMapper, readOnlyRegistry, null);
        when(subagentRunner.runSub(any(), any(), isNull(), any()))
            .thenReturn("无 reporter 测试报告。");

        ToolCall call = ToolCall.of("c1", "spawn_subagent",
            "{\"task_prompt\":\"测试 null reporter\"}");
        ToolExecutionContext context = new ToolExecutionContext(tempDir);

        ToolResult result = tool.execute(call, context);

        assertThat(result.error()).isFalse();
        assertThat(result.output()).contains("无 reporter 测试报告。");
    }
}
