package com.tinyclaw.application.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.adapters.reporter.NoOpReporter;
import com.tinyclaw.adapters.session.InMemorySessionService;
import com.tinyclaw.adapters.tools.filesystem.ReadFileTool;
import com.tinyclaw.adapters.tools.filesystem.WriteFileTool;
import com.tinyclaw.adapters.tools.filesystem.WorkspacePathResolver;
import com.tinyclaw.application.approval.ApprovalGatePolicy;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.application.persistence.ToolExecutionRecord;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.llm.LlmException;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEngineTest {

    @TempDir
    Path workspace;

    private ToolRegistry toolRegistry;
    private SessionService sessionService;
    private Reporter reporter;
    private PromptComposer promptComposer;
    private Clock clock;

    @BeforeEach
    void setUp() {
        WorkspacePathResolver pathResolver = new WorkspacePathResolver();
        ObjectMapper objectMapper = new ObjectMapper();
        toolRegistry = new ToolRegistry(List.of(
            new WriteFileTool(pathResolver, objectMapper),
            new ReadFileTool(pathResolver, objectMapper)
        ));
        sessionService = new InMemorySessionService();
        reporter = new NoOpReporter();
        promptComposer = new PromptComposer();
        clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void directCompletionWithoutToolCalls() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("Hello, user!", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);

        AgentRunResult result = engine.run(
            startRun(3),
            createSession(),
            "Say hello",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.finalMessage()).isEqualTo("Hello, user!");
        assertThat(result.turnCount()).isEqualTo(1);
        assertThat(result.errorReason()).isNull();
    }

    @Test
    void singleToolCallThenCompletes() throws Exception {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "write_file", "{\"path\":\"out.txt\",\"content\":\"data\"}")
            ), null),
            new LlmResponse("done", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);

        AgentRunResult result = engine.run(
            startRun(3),
            createSession(),
            "Write a file",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.finalMessage()).isEqualTo("done");
        assertThat(result.turnCount()).isEqualTo(2);
        assertThat(Files.readString(workspace.resolve("out.txt"))).isEqualTo("data");
    }

    @Test
    void multiToolCallSequence() throws Exception {
        Files.writeString(workspace.resolve("src.txt"), "hello");

        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "read_file", "{\"path\":\"src.txt\"}")
            ), null),
            new LlmResponse("", List.of(
                ToolCall.of("t2", "write_file", "{\"path\":\"dst.txt\",\"content\":\"read: hello\"}")
            ), null),
            new LlmResponse("finished", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);

        AgentRunResult result = engine.run(
            startRun(5),
            createSession(),
            "Copy file",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.finalMessage()).isEqualTo("finished");
        assertThat(result.turnCount()).isEqualTo(3);
        assertThat(Files.readString(workspace.resolve("dst.txt"))).isEqualTo("read: hello");
    }

    @Test
    void maxTurnsExceeded() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "write_file", "{\"path\":\"a.txt\",\"content\":\"1\"}")
            ), null),
            new LlmResponse("", List.of(
                ToolCall.of("t2", "write_file", "{\"path\":\"b.txt\",\"content\":\"2\"}")
            ), null),
            new LlmResponse("", List.of(
                ToolCall.of("t3", "write_file", "{\"path\":\"c.txt\",\"content\":\"3\"}")
            ), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);

        AgentRunResult result = engine.run(
            startRun(2),
            createSession(),
            "Write many files",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isFalse();
        assertThat(result.turnCount()).isEqualTo(2);
        assertThat(result.errorReason()).contains("Max turns");
    }

    @Test
    void llmExceptionCausesRunFailure() {
        LlmGateway explodingGateway = request -> {
            throw new LlmException("network timeout");
        };
        AgentEngine engine = new AgentEngine(explodingGateway, toolRegistry, promptComposer, reporter, sessionService, clock);

        AgentRunResult result = engine.run(
            startRun(3),
            createSession(),
            "Do something",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorReason()).contains("LLM generation failed").contains("network timeout");
        assertThat(result.turnCount()).isEqualTo(1);
    }

    @Test
    void toolFailureIsObservedAndRunFails() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("Could not read file", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);

        AgentRunResult result = engine.run(
            startRun(3),
            createSession(),
            "Read missing file",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isFalse();
        assertThat(result.finalMessage()).isEqualTo("Could not read file");
        assertThat(result.errorReason()).contains("read_file").contains("failed");
        assertThat(result.turnCount()).isEqualTo(2);

        // Verify session contains the error observation for ReAct memory
        List<Message> memory = sessionService.getWorkingMemory("session-1");
        assertThat(memory).hasSize(4); // user + assistant(t1) + observation(t1) + assistant(final)
        Message observation = memory.get(2);
        assertThat(observation.role().name()).isEqualTo("USER");
        assertThat(observation.toolCallId()).isEqualTo("t1");
        assertThat(observation.content()).contains("File does not exist");
    }

    @Test
    void sessionContainsFullConversation() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "write_file", "{\"path\":\"x.txt\",\"content\":\"y\"}")
            ), null),
            new LlmResponse("ok", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);

        engine.run(startRun(3), createSession(), "Test", new ToolExecutionContext(workspace));

        List<Message> memory = sessionService.getWorkingMemory("session-1");
        // user prompt + assistant with tool calls + tool observation + assistant final
        assertThat(memory).hasSize(4);
        assertThat(memory.get(0).role().name()).isEqualTo("USER");
        assertThat(memory.get(0).content()).isEqualTo("Test");
        assertThat(memory.get(1).role().name()).isEqualTo("ASSISTANT");
        assertThat(memory.get(1).toolCalls()).hasSize(1);
        assertThat(memory.get(2).role().name()).isEqualTo("USER");
        assertThat(memory.get(2).toolCallId()).isEqualTo("t1");
        assertThat(memory.get(3).role().name()).isEqualTo("ASSISTANT");
        assertThat(memory.get(3).content()).isEqualTo("ok");
    }

    @Test
    void recordsLlmRequestsIncludingSystemPromptAndHistory() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("ok", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);

        engine.run(startRun(3), createSession(), "Hello", new ToolExecutionContext(workspace));

        List<LlmRequest> requests = fakeLlm.recordedRequests();
        assertThat(requests).hasSize(1);
        LlmRequest req = requests.get(0);
        // First message should be system prompt
        assertThat(req.messages().get(0).role().name()).isEqualTo("SYSTEM");
        // Should contain user prompt in history
        assertThat(req.messages().stream()
            .anyMatch(m -> m.role().name().equals("USER") && m.content().equals("Hello")))
            .isTrue();
    }

    @Test
    void withLlmGatewayReplacesGatewayOnly() {
        FakeLlmGateway first = new FakeLlmGateway(List.of(
            new LlmResponse("first", List.of(), null)
        ));
        FakeLlmGateway second = new FakeLlmGateway(List.of(
            new LlmResponse("second", List.of(), null)
        ));

        AgentEngine original = new AgentEngine(first, toolRegistry, promptComposer, reporter, sessionService, clock);
        AgentEngine swapped = original.withLlmGateway(second);

        AgentRunResult result = swapped.run(startRun(1), createSession(), "test", new ToolExecutionContext(workspace));

        assertThat(result.success()).isTrue();
        assertThat(result.finalMessage()).isEqualTo("second");
        assertThat(first.isExhausted()).isFalse(); // first was never consumed
        assertThat(second.isExhausted()).isTrue();  // second was consumed
    }

    @Test
    void toolFailureCausesFinalResultToBeFailed() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("Could not read", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);

        AgentRunResult result = engine.run(
            startRun(3), createSession(), "Read missing",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorReason()).contains("read_file").contains("failed");
        assertThat(result.finalMessage()).isEqualTo("Could not read");
        assertThat(result.turnCount()).isEqualTo(2);

        // Session still contains the error observation for ReAct memory
        List<Message> memory = sessionService.getWorkingMemory("session-1");
        assertThat(memory).hasSize(4);
        assertThat(memory.get(2).toolCallId()).isEqualTo("t1");
    }

    @Test
    void persistsToolExecutionsWhenRepositoryProvided() throws Exception {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "write_file", "{\"path\":\"out.txt\",\"content\":\"data\"}")
            ), null),
            new LlmResponse("done", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);

        List<ToolExecutionRecord> captured = new ArrayList<>();
        ToolExecutionRepositoryPort toolRepo = new ToolExecutionRepositoryPort() {
            @Override
            public void append(String runId, ToolExecutionRecord record) {
                captured.add(record);
            }
            @Override
            public List<ToolExecutionRecord> findByRunId(String runId) {
                return List.of();
            }
        };

        AgentRunResult result = engine.run(
            startRun(3), createSession(), "Write a file",
            new ToolExecutionContext(workspace), toolRepo
        );

        assertThat(result.success()).isTrue();
        assertThat(captured).hasSize(1);
        ToolExecutionRecord record = captured.get(0);
        assertThat(record.runId()).isEqualTo("run-1");
        assertThat(record.sessionId()).isEqualTo("session-1");
        assertThat(record.toolName()).isEqualTo("write_file");
        assertThat(record.stepId()).isEqualTo("t1");
        assertThat(record.isError()).isFalse();
        assertThat(record.startedAt()).isNotNull();
        assertThat(record.completedAt()).isNotNull();
        assertThat(record.completedAt()).isAfterOrEqualTo(record.startedAt());
    }

    @Test
    void persistsFailedToolExecutionsWhenRepositoryProvided() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("Could not read", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);

        List<ToolExecutionRecord> captured = new ArrayList<>();
        ToolExecutionRepositoryPort toolRepo = new ToolExecutionRepositoryPort() {
            @Override
            public void append(String runId, ToolExecutionRecord record) {
                captured.add(record);
            }
            @Override
            public List<ToolExecutionRecord> findByRunId(String runId) {
                return List.of();
            }
        };

        AgentRunResult result = engine.run(
            startRun(3), createSession(), "Read missing",
            new ToolExecutionContext(workspace), toolRepo
        );

        assertThat(result.success()).isFalse();
        assertThat(captured).hasSize(1);
        ToolExecutionRecord record = captured.get(0);
        assertThat(record.toolName()).isEqualTo("read_file");
        assertThat(record.isError()).isTrue();
        assertThat(record.output()).contains("File does not exist");
    }

    @Test
    void doesNotPersistToolExecutionsWhenRepositoryNull() throws Exception {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "write_file", "{\"path\":\"out.txt\",\"content\":\"data\"}")
            ), null),
            new LlmResponse("done", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);

        // Call the 4-arg overload (repository = null)
        AgentRunResult result = engine.run(
            startRun(3), createSession(), "Write a file",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isTrue();
        // If no NPE was thrown and result is correct, backward compatibility is preserved
    }

    @Test
    void approvalGateBlocksToolAndRunFails() {
        InMemoryApprovalRepository approvalRepo = new InMemoryApprovalRepository();
        ToolRegistry gatedRegistry = new ToolRegistry(List.of(
            new WriteFileTool(new WorkspacePathResolver(), new ObjectMapper())
        ), List.of(
            new ApprovalGatePolicy(approvalRepo, List.of("write_file"), clock)
        ));

        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "write_file", "{\"path\":\"out.txt\",\"content\":\"data\"}")
            ), null),
            new LlmResponse("Could not write", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, gatedRegistry, promptComposer, reporter, sessionService, clock);

        AgentRunResult result = engine.run(
            startRun(3), createSession(), "Write a file",
            new ToolExecutionContext(workspace, "run-1", "session-1")
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorReason()).contains("write_file").contains("failed");

        List<Message> memory = sessionService.getWorkingMemory("session-1");
        assertThat(memory).hasSize(4);
        assertThat(memory.get(2).content()).contains("Approval required");

        assertThat(approvalRepo.requests).hasSize(1);
        assertThat(approvalRepo.requests.get(0).status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(approvalRepo.requests.get(0).toolCallId()).isEqualTo("t1");
    }

    private AgentRun startRun(int maxTurns) {
        return AgentRun.start("run-1", "session-1", maxTurns, clock.instant());
    }

    private Session createSession() {
        return Session.create("session-1", workspace.toAbsolutePath().toString(), clock.instant());
    }

    private static class InMemoryApprovalRepository implements ApprovalRepositoryPort {
        final java.util.List<ApprovalRequest> requests = new java.util.ArrayList<>();

        @Override
        public void save(ApprovalRequest request) {
            requests.add(request);
        }

        @Override
        public java.util.Optional<ApprovalRequest> findById(String id) {
            return requests.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public java.util.Optional<ApprovalRequest> findByRunIdAndToolCallId(String runId, String toolCallId) {
            return requests.stream()
                .filter(r -> r.runId().equals(runId) && r.toolCallId().equals(toolCallId))
                .findFirst();
        }

        @Override
        public java.util.List<ApprovalRequest> findByRunId(String runId) {
            return requests.stream().filter(r -> r.runId().equals(runId)).toList();
        }

        @Override
        public java.util.List<ApprovalRequest> findByStatus(ApprovalStatus status) {
            return requests.stream().filter(r -> r.status() == status).toList();
        }

        @Override
        public java.util.List<ApprovalRequest> findAll() {
            return java.util.List.copyOf(requests);
        }

        @Override
        public void update(ApprovalRequest request) {
            requests.removeIf(r -> r.id().equals(request.id()));
            requests.add(request);
        }

        @Override
        public boolean claimForResume(String approvalId, java.time.Instant now) {
            return false;
        }
    }
}
