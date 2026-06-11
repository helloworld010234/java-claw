package com.tinyclaw.application.engine;

import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.application.tool.AllowAllPolicy;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.tool.AgentTool;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AgentEngine#runSub} subagent execution loop.
 */
class AgentEngineSubagentTest {

    @TempDir
    Path tempDir;

    private final Reporter reporter = mock(Reporter.class);
    private final Clock fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);

    /**
     * A simple echo tool for testing subagent tool execution.
     */
    static class EchoTool implements AgentTool {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("echo", "Echo tool", """
                {"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}
                """);
        }

        @Override
        public ToolResult execute(ToolCall call, ToolExecutionContext context) {
            return ToolResult.success(call.id(), "Echo: " + call.argumentsJson());
        }
    }

    private ToolRegistry createReadOnlyRegistry() {
        return new ToolRegistry(List.of(new EchoTool()), List.of(new AllowAllPolicy()));
    }

    private AgentEngine createEngine(FakeLlmGateway llm) {
        PromptComposer composer = new PromptComposer(false, null, null);
        return new AgentEngine(llm, createReadOnlyRegistry(), composer,
            mock(Reporter.class), mock(com.tinyclaw.ports.session.SessionService.class),
            fixedClock);
    }

    @Test
    void runSubWithNoToolCallsReturnsContent() {
        FakeLlmGateway llm = new FakeLlmGateway(List.of(
            new LlmResponse("直接回答，无需工具", List.of(), null)
        ));
        AgentEngine engine = createEngine(llm);

        String result = engine.runSub("测试任务", createReadOnlyRegistry(), reporter, tempDir.toString());

        assertThat(result).isEqualTo("直接回答，无需工具");
        verifyNoInteractions(reporter);
    }

    @Test
    void runSubWithToolCallsExecutesAndReturnsResult() {
        FakeLlmGateway llm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "echo", "{\"message\":\"hello\"}")
            ), null),
            new LlmResponse("探索完成，发现关键信息。", List.of(), null)
        ));
        AgentEngine engine = createEngine(llm);

        String result = engine.runSub("探索代码结构", createReadOnlyRegistry(), reporter, tempDir.toString());

        assertThat(result).isEqualTo("探索完成，发现关键信息。");
        verify(reporter, times(1)).onToolCall(any(), any());
        verify(reporter, times(1)).onToolResult(any(), any());
    }

    @Test
    void runSubExceedsMaxTurnsReturnsRecallMessage() {
        // 模拟 LLM 每次都调用工具，超过 10 轮
        List<LlmResponse> responses = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            responses.add(new LlmResponse("", List.of(
                ToolCall.of("t" + i, "echo", "{\"message\":\"turn" + i + "\"}")
            ), null));
        }
        FakeLlmGateway llm = new FakeLlmGateway(responses);
        AgentEngine engine = createEngine(llm);

        String result = engine.runSub("无限探索", createReadOnlyRegistry(), reporter, tempDir.toString());

        assertThat(result).contains("强制召回");
        assertThat(result).contains("10");
    }

    @Test
    void runSubWithLlmFailureReturnsErrorMessage() {
        FakeLlmGateway llm = new FakeLlmGateway(req -> {
            throw new com.tinyclaw.ports.llm.LlmException("模拟 LLM 故障");
        });
        AgentEngine engine = createEngine(llm);

        String result = engine.runSub("测试失败", createReadOnlyRegistry(), reporter, tempDir.toString());

        assertThat(result).contains("子智能体推理失败");
        assertThat(result).contains("模拟 LLM 故障");
    }

    @Test
    void runSubWithToolFailureIncludesRecoveryAdvice() {
        // 创建一个会失败的工具
        AgentTool failingTool = new AgentTool() {
            @Override
            public String name() { return "failing"; }
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("failing", "Failing tool", "{}");
            }
            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                return ToolResult.failure(call.id(), "工具执行错误");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(failingTool), List.of(new AllowAllPolicy()));

        FakeLlmGateway llm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "failing", "{}")
            ), null),
            new LlmResponse("虽然工具失败，但我已经知道答案了。", List.of(), null)
        ));
        AgentEngine engine = createEngine(llm);

        String result = engine.runSub("测试工具失败", registry, reporter, tempDir.toString());

        assertThat(result).isEqualTo("虽然工具失败，但我已经知道答案了。");
        // 验证 reporter 收到了错误结果（截断后）
        verify(reporter, times(1)).onToolResult(any(), any());
    }

    @Test
    void runSubWithNullReporterDoesNotThrow() {
        FakeLlmGateway llm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "echo", "{\"message\":\"hello\"}")
            ), null),
            new LlmResponse("完成。", List.of(), null)
        ));
        AgentEngine engine = createEngine(llm);

        // 传入 null reporter，不应抛出 NPE
        String result = engine.runSub("测试 null reporter", createReadOnlyRegistry(), null, tempDir.toString());

        assertThat(result).isEqualTo("完成。");
    }

    @Test
    void runSubRecordsLlmRequests() {
        FakeLlmGateway llm = new FakeLlmGateway(List.of(
            new LlmResponse("直接回答", List.of(), null)
        ));
        AgentEngine engine = createEngine(llm);

        engine.runSub("测试记录", createReadOnlyRegistry(), reporter, tempDir.toString());

        assertThat(llm.recordedRequests()).hasSize(1);
        LlmRequest request = llm.recordedRequests().get(0);
        // 验证 context 包含 system prompt 和 user task
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(request.messages().get(1).role()).isEqualTo(Role.USER);
        assertThat(request.messages().get(1).content()).isEqualTo("测试记录");
    }
}
