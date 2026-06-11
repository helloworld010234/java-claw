package com.tinyclaw.application.engine;

import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.application.tool.AllowAllPolicy;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import com.tinyclaw.ports.observability.TraceReporter;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.AgentTool;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests verifying {@link AgentEngine} correctly invokes {@link TraceReporter}
 * during {@code run()} and {@code runSub()}.
 */
class AgentEngineTraceTest {

    @TempDir
    Path tempDir;

    private final TraceReporter traceReporter = mock(TraceReporter.class);
    private final TraceReporter.SpanHandle spanHandle = mock(TraceReporter.SpanHandle.class);
    private final Reporter reporter = mock(Reporter.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final Clock fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);

    static class EchoTool implements AgentTool {
        @Override
        public String name() { return "echo"; }
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("echo", "Echo", "{}");
        }
        @Override
        public ToolResult execute(ToolCall call, ToolExecutionContext context) {
            return ToolResult.success(call.id(), "Echo: " + call.argumentsJson());
        }
    }

    private AgentEngine createEngine(FakeLlmGateway llm) {
        PromptComposer composer = new PromptComposer(false, null, null);
        ToolRegistry registry = new ToolRegistry(List.of(new EchoTool()), List.of(new AllowAllPolicy()));
        return new AgentEngine(llm, registry, composer, reporter, sessionService, fixedClock,
            new AgentContextBuilder(composer, new WorkingMemorySelector(), new ContextCompactor()),
            new ToolFailureRecoveryAdvisor(), null, traceReporter);
    }

    @Test
    void runStartsAndEndsSpan() {
        when(traceReporter.startSpan(any(), any())).thenReturn(spanHandle);
        when(sessionService.getWorkingMemory(any())).thenReturn(List.of());

        FakeLlmGateway llm = new FakeLlmGateway(List.of(
            new LlmResponse("Done", List.of(), null)
        ));
        AgentEngine engine = createEngine(llm);
        Session session = Session.create("sess-1", tempDir.toString(), fixedClock.instant());
        AgentRun run = AgentRun.start("run-1", "sess-1", 10, fixedClock.instant());

        engine.run(run, session, "Hello", new ToolExecutionContext(tempDir));

        verify(traceReporter).startSpan(eq("AgentEngine.run"), any());
        verify(traceReporter).endSpan(spanHandle);
    }

    @Test
    void runRecordsToolExecutionEvent() {
        when(traceReporter.startSpan(any(), any())).thenReturn(spanHandle);
        when(sessionService.getWorkingMemory(any())).thenReturn(List.of());

        FakeLlmGateway llm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "echo", "{\"msg\":\"hi\"}")
            ), null),
            new LlmResponse("Done", List.of(), null)
        ));
        AgentEngine engine = createEngine(llm);
        Session session = Session.create("sess-2", tempDir.toString(), fixedClock.instant());
        AgentRun run = AgentRun.start("run-2", "sess-2", 10, fixedClock.instant());

        engine.run(run, session, "Hello", new ToolExecutionContext(tempDir));

        verify(traceReporter).recordEvent(eq(spanHandle), eq("tool.execute"), any());
        verify(traceReporter).endSpan(spanHandle);
    }

    @Test
    void runAddsErrorAttributeOnFailure() {
        when(traceReporter.startSpan(any(), any())).thenReturn(spanHandle);
        when(sessionService.getWorkingMemory(any())).thenReturn(List.of());

        FakeLlmGateway llm = new FakeLlmGateway(req -> {
            throw new com.tinyclaw.ports.llm.LlmException("Boom");
        });
        AgentEngine engine = createEngine(llm);
        Session session = Session.create("sess-3", tempDir.toString(), fixedClock.instant());
        AgentRun run = AgentRun.start("run-3", "sess-3", 10, fixedClock.instant());

        engine.run(run, session, "Hello", new ToolExecutionContext(tempDir));

        verify(traceReporter).addAttribute(spanHandle, "error", "LLM generation failed: Boom");
        verify(traceReporter).endSpan(spanHandle);
    }

    @Test
    void runSubStartsAndEndsSpan() {
        when(traceReporter.startSpan(any(), any())).thenReturn(spanHandle);

        FakeLlmGateway llm = new FakeLlmGateway(List.of(
            new LlmResponse("Sub result", List.of(), null)
        ));
        AgentEngine engine = createEngine(llm);
        ToolRegistry readOnlyRegistry = new ToolRegistry(List.of(), List.of());

        engine.runSub("Explore", readOnlyRegistry, null, tempDir.toString());

        verify(traceReporter).startSpan(eq("AgentEngine.runSub"), any());
        verify(traceReporter).endSpan(spanHandle);
    }

    @Test
    void runSubRecordsToolEvent() {
        when(traceReporter.startSpan(any(), any())).thenReturn(spanHandle);

        FakeLlmGateway llm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "echo", "{}")
            ), null),
            new LlmResponse("Done", List.of(), null)
        ));
        AgentEngine engine = createEngine(llm);
        ToolRegistry readOnlyRegistry = new ToolRegistry(List.of(new EchoTool()), List.of(new AllowAllPolicy()));

        engine.runSub("Explore", readOnlyRegistry, null, tempDir.toString());

        verify(traceReporter).recordEvent(eq(spanHandle), eq("subagent.tool.execute"), any());
        verify(traceReporter).endSpan(spanHandle);
    }

    @Test
    void runSubAddsErrorOnMaxTurns() {
        when(traceReporter.startSpan(any(), any())).thenReturn(spanHandle);

        List<LlmResponse> responses = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            responses.add(new LlmResponse("", List.of(
                ToolCall.of("t" + i, "echo", "{}")
            ), null));
        }
        FakeLlmGateway llm = new FakeLlmGateway(responses);
        AgentEngine engine = createEngine(llm);
        ToolRegistry readOnlyRegistry = new ToolRegistry(List.of(new EchoTool()), List.of(new AllowAllPolicy()));

        engine.runSub("Explore", readOnlyRegistry, null, tempDir.toString());

        verify(traceReporter).addAttribute(spanHandle, "error", "max_turns_exceeded");
        verify(traceReporter).endSpan(spanHandle);
    }
}
