package com.tinyclaw.application.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.adapters.reporter.NoOpReporter;
import com.tinyclaw.adapters.session.InMemorySessionService;
import com.tinyclaw.adapters.tools.filesystem.GlobFilesTool;
import com.tinyclaw.adapters.tools.filesystem.ReadFileTool;
import com.tinyclaw.adapters.tools.filesystem.SearchTextTool;
import com.tinyclaw.adapters.tools.filesystem.WriteFileTool;
import com.tinyclaw.adapters.tools.filesystem.WorkspacePathResolver;
import com.tinyclaw.application.approval.ApprovalGatePolicy;
import com.tinyclaw.application.engine.AgentContextBuilder;
import com.tinyclaw.application.engine.ContextCompactor;
import com.tinyclaw.application.engine.ToolFailureRecoveryAdvisor;
import com.tinyclaw.application.engine.WorkingMemorySelector;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.ports.persistence.ToolExecutionRecord;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.ports.tool.AgentTool;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
            new ReadFileTool(pathResolver, objectMapper),
            new GlobFilesTool(pathResolver, objectMapper),
            new SearchTextTool(pathResolver, objectMapper)
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
    void searchToolObservationFlowsBackToLlm() throws Exception {
        Files.writeString(workspace.resolve("data.txt"), "the answer is 42");

        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "search_text", "{\"query\":\"answer\"}")
            ), null),
            new LlmResponse("The answer is 42", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);

        AgentRunResult result = engine.run(
            startRun(3),
            createSession(),
            "Find the answer",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.finalMessage()).isEqualTo("The answer is 42");
        assertThat(result.turnCount()).isEqualTo(2);

        List<Message> memory = sessionService.getWorkingMemory("session-1");
        assertThat(memory).anyMatch(m -> m.content().contains("data.txt:1: the answer is 42"));
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
    void withModelNameReplacesModelNameOnly() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("ok", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);
        AgentEngine renamed = engine.withModelName("custom-model");

        renamed.run(startRun(1), createSession(), "test", new ToolExecutionContext(workspace));

        List<LlmRequest> requests = fakeLlm.recordedRequests();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).model()).isEqualTo("custom-model");
    }

    @Test
    void modelNameDefaultsToEmptyString() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("ok", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);

        engine.run(startRun(1), createSession(), "test", new ToolExecutionContext(workspace));

        List<LlmRequest> requests = fakeLlm.recordedRequests();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).model()).isEqualTo("");
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

    @Test
    void usesAgentContextBuilderForLlmRequest() {
        Message markedSystem = Message.system("MARKED-SYSTEM");
        PromptComposer markingComposer = new PromptComposer() {
            @Override
            public Message compose(String workspaceRoot) {
                return markedSystem;
            }
        };
        AgentContextBuilder customBuilder = new AgentContextBuilder(
            markingComposer,
            new WorkingMemorySelector(),
            new ContextCompactor()
        );
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("ok", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(
            fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock,
            customBuilder, new ToolFailureRecoveryAdvisor()
        );

        engine.run(startRun(3), createSession(), "Hello", new ToolExecutionContext(workspace));

        List<LlmRequest> requests = fakeLlm.recordedRequests();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).messages().get(0)).isEqualTo(markedSystem);
    }

    @Test
    void injectsRecoveryHintIntoErrorObservation() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("Could not read", List.of(), null)
        ));
        ToolFailureRecoveryAdvisor advisor = new ToolFailureRecoveryAdvisor();
        AgentEngine engine = new AgentEngine(
            fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock,
            new AgentContextBuilder(promptComposer, new WorkingMemorySelector(), new ContextCompactor()),
            advisor
        );

        engine.run(startRun(3), createSession(), "Read missing", new ToolExecutionContext(workspace));

        List<Message> memory = sessionService.getWorkingMemory("session-1");
        Message observation = memory.get(2);
        assertThat(observation.role().name()).isEqualTo("USER");
        assertThat(observation.toolCallId()).isEqualTo("t1");
        assertThat(observation.content()).contains("File does not exist");
        assertThat(observation.content()).contains("[Recovery hint]:");
    }

    @Test
    void injectsReminderAfterThreeConsecutiveFailures() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("", List.of(
                ToolCall.of("t2", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("", List.of(
                ToolCall.of("t3", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("Stopped", List.of(), null)
        ));
        ToolFailureRecoveryAdvisor advisor = new ToolFailureRecoveryAdvisor();
        AgentEngine engine = new AgentEngine(
            fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock,
            new AgentContextBuilder(promptComposer, new WorkingMemorySelector(), new ContextCompactor()),
            advisor
        );

        AgentRunResult result = engine.run(
            startRun(10), createSession(), "Keep reading missing file", new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isFalse();
        List<Message> memory = sessionService.getWorkingMemory("session-1");
        boolean hasReminder = memory.stream()
            .anyMatch(m -> m.role().name().equals("USER")
                && m.content().contains("SYSTEM REMINDER")
                && m.content().contains("read_file"));
        assertThat(hasReminder).isTrue();
    }

    @Test
    void failureReminderStateIsIsolatedAcrossRuns() {
        // First run: three consecutive failures trigger reminder
        FakeLlmGateway fakeLlmRun1 = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("", List.of(
                ToolCall.of("t2", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("", List.of(
                ToolCall.of("t3", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("Stopped", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(
            fakeLlmRun1, toolRegistry, promptComposer, reporter, sessionService, clock,
            new AgentContextBuilder(promptComposer, new WorkingMemorySelector(), new ContextCompactor()),
            new ToolFailureRecoveryAdvisor()
        );

        AgentRunResult result1 = engine.run(
            startRun(10), createSession(), "Run 1", new ToolExecutionContext(workspace)
        );
        assertThat(result1.success()).isFalse();
        List<Message> memory1 = sessionService.getWorkingMemory("session-1");
        long reminderCount1 = memory1.stream()
            .filter(m -> m.role().name().equals("USER") && m.content().contains("SYSTEM REMINDER"))
            .count();
        assertThat(reminderCount1).isOne();

        // Second run: same engine (via withLlmGateway), same tool/args, only two failures -> no reminder
        FakeLlmGateway fakeLlmRun2 = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t4", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("", List.of(
                ToolCall.of("t5", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("Stopped", List.of(), null)
        ));
        AgentEngine sameEngine = engine.withLlmGateway(fakeLlmRun2);

        AgentRunResult result2 = sameEngine.run(
            AgentRun.start("run-2", "session-2", 10, clock.instant()),
            Session.create("session-2", workspace.toAbsolutePath().toString(), clock.instant()),
            "Run 2", new ToolExecutionContext(workspace)
        );
        assertThat(result2.success()).isFalse();
        List<Message> memory2 = sessionService.getWorkingMemory("session-2");
        long reminderCount2 = memory2.stream()
            .filter(m -> m.role().name().equals("USER") && m.content().contains("SYSTEM REMINDER"))
            .count();
        assertThat(reminderCount2).isZero();
    }

    @Test
    void reminderCounterResetsAfterToolSuccess() throws Exception {
        // Sequence: fail, fail, success (write_file), fail -> no reminder because counter reset
        Files.writeString(workspace.resolve("exists.txt"), "data");
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("f1", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("", List.of(
                ToolCall.of("f2", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("", List.of(
                ToolCall.of("s1", "write_file", "{\"path\":\"exists.txt\",\"content\":\"ok\",\"overwrite\":true}")
            ), null),
            new LlmResponse("", List.of(
                ToolCall.of("f3", "read_file", "{\"path\":\"missing.txt\"}")
            ), null),
            new LlmResponse("Done", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(
            fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock,
            new AgentContextBuilder(promptComposer, new WorkingMemorySelector(), new ContextCompactor()),
            new ToolFailureRecoveryAdvisor()
        );

        AgentRunResult result = engine.run(
            startRun(10), createSession(), "Mixed results", new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isFalse();
        List<Message> memory = sessionService.getWorkingMemory("session-1");
        long reminderCount = memory.stream()
            .filter(m -> m.role().name().equals("USER") && m.content().contains("SYSTEM REMINDER"))
            .count();
        assertThat(reminderCount).isZero();
    }

    @Test
    void maxToolCallsPerTurnExceededFailsRun() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "write_file", "{\"path\":\"a.txt\",\"content\":\"1\"}"),
                ToolCall.of("t2", "write_file", "{\"path\":\"b.txt\",\"content\":\"2\"}"),
                ToolCall.of("t3", "write_file", "{\"path\":\"c.txt\",\"content\":\"3\"}"),
                ToolCall.of("t4", "write_file", "{\"path\":\"d.txt\",\"content\":\"4\"}"),
                ToolCall.of("t5", "write_file", "{\"path\":\"e.txt\",\"content\":\"5\"}"),
                ToolCall.of("t6", "write_file", "{\"path\":\"f.txt\",\"content\":\"6\"}"),
                ToolCall.of("t7", "write_file", "{\"path\":\"g.txt\",\"content\":\"7\"}"),
                ToolCall.of("t8", "write_file", "{\"path\":\"h.txt\",\"content\":\"8\"}"),
                ToolCall.of("t9", "write_file", "{\"path\":\"i.txt\",\"content\":\"9\"}")
            ), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock);

        AgentRunResult result = engine.run(
            startRun(3), createSession(), "Write many files",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorReason()).contains("Tool calls per turn exceeded limit");
        assertThat(result.turnCount()).isEqualTo(1);
    }

    @Test
    void customMaxToolCallsPerTurnIsRespected() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "write_file", "{\"path\":\"a.txt\",\"content\":\"1\"}"),
                ToolCall.of("t2", "write_file", "{\"path\":\"b.txt\",\"content\":\"2\"}"),
                ToolCall.of("t3", "write_file", "{\"path\":\"c.txt\",\"content\":\"3\"}")
            ), null)
        ));
        // Default is 8, set to 2
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock,
            new AgentContextBuilder(promptComposer, new WorkingMemorySelector(), new ContextCompactor()),
            new ToolFailureRecoveryAdvisor(), null,
            new com.tinyclaw.ports.observability.NoOpTraceReporter(), 2);

        AgentRunResult result = engine.run(
            startRun(3), createSession(), "Write files",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorReason()).contains("Tool calls per turn exceeded limit: 3 > 2");
    }

    @Test
    void customMaxToolCallsPerTurnAllowsMoreWhenIncreased() throws Exception {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "write_file", "{\"path\":\"a.txt\",\"content\":\"1\"}"),
                ToolCall.of("t2", "write_file", "{\"path\":\"b.txt\",\"content\":\"2\"}"),
                ToolCall.of("t3", "write_file", "{\"path\":\"c.txt\",\"content\":\"3\"}")
            ), null),
            new LlmResponse("done", List.of(), null)
        ));
        // Default is 8, set to 3
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock,
            new AgentContextBuilder(promptComposer, new WorkingMemorySelector(), new ContextCompactor()),
            new ToolFailureRecoveryAdvisor(), null,
            new com.tinyclaw.ports.observability.NoOpTraceReporter(), 3);

        AgentRunResult result = engine.run(
            startRun(3), createSession(), "Write files",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.turnCount()).isEqualTo(2);
        assertThat(Files.readString(workspace.resolve("a.txt"))).isEqualTo("1");
        assertThat(Files.readString(workspace.resolve("b.txt"))).isEqualTo("2");
        assertThat(Files.readString(workspace.resolve("c.txt"))).isEqualTo("3");
    }

    @Test
    void thinkingPhaseProducesIntermediateReasoning() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("Let me think about this...", List.of(), null),
            new LlmResponse("Hello, user!", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock)
            .withEnableThinking(true);

        AgentRunResult result = engine.run(
            startRun(3),
            createSession(),
            "Say hello",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.finalMessage()).isEqualTo("Let me think about this...\nHello, user!");
        assertThat(result.turnCount()).isEqualTo(1);

        // Verify thinking request had no tools
        List<LlmRequest> requests = fakeLlm.recordedRequests();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).tools()).isEmpty();
        assertThat(requests.get(1).tools()).isNotEmpty();
    }

    @Test
    void thinkingPhaseWithEmptyThinkingContentSkipsAppending() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(), null),
            new LlmResponse("Hello, user!", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock)
            .withEnableThinking(true);

        AgentRunResult result = engine.run(
            startRun(3),
            createSession(),
            "Say hello",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.finalMessage()).isEqualTo("Hello, user!");
    }

    @Test
    void thinkingPhaseFailureIsReportedSeparately() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(req -> {
            if (req.tools().isEmpty()) {
                throw new LlmException("Thinking model overloaded");
            }
            return new LlmResponse("Hello", List.of(), null);
        });
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock)
            .withEnableThinking(true);

        AgentRunResult result = engine.run(
            startRun(3),
            createSession(),
            "Say hello",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorReason()).contains("Thinking phase failed").contains("Thinking model overloaded");
    }

    @Test
    void thinkingPhaseContentIncludedInActionContext() {
        FakeLlmGateway fakeLlm = new FakeLlmGateway(req -> {
            // In action phase, context should contain the thinking assistant message
            boolean hasThinking = req.messages().stream()
                .anyMatch(m -> m.role().name().equals("ASSISTANT") && m.content().equals("I will write a file"));
            if (hasThinking) {
                return new LlmResponse("Done", List.of(), null);
            }
            return new LlmResponse("I will write a file", List.of(), null);
        });
        AgentEngine engine = new AgentEngine(fakeLlm, toolRegistry, promptComposer, reporter, sessionService, clock)
            .withEnableThinking(true);

        AgentRunResult result = engine.run(
            startRun(3),
            createSession(),
            "Write a file",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.finalMessage()).isEqualTo("I will write a file\nDone");
    }

    @Test
    void multipleToolCallsExecuteConcurrently() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch proceed = new CountDownLatch(1);

        AgentTool tool1 = new AgentTool() {
            @Override
            public String name() { return "tool_1"; }
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("tool_1", "Tool 1", "{}");
            }
            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                bothStarted.countDown();
                try {
                    proceed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.success(call.id(), "result-1");
            }
        };

        AgentTool tool2 = new AgentTool() {
            @Override
            public String name() { return "tool_2"; }
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("tool_2", "Tool 2", "{}");
            }
            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                bothStarted.countDown();
                try {
                    proceed.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.success(call.id(), "result-2");
            }
        };

        ToolRegistry concurrentRegistry = new ToolRegistry(List.of(tool1, tool2));
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "tool_1", "{}"),
                ToolCall.of("t2", "tool_2", "{}")
            ), null),
            new LlmResponse("done", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, concurrentRegistry, promptComposer, reporter, sessionService, clock);

        // Run in a separate thread so we can release the latch after verifying both started
        java.util.concurrent.atomic.AtomicReference<AgentRunResult> resultRef = new java.util.concurrent.atomic.AtomicReference<>();
        Thread runner = new Thread(() -> {
            resultRef.set(engine.run(
                startRun(3),
                createSession(),
                "Run concurrent tools",
                new ToolExecutionContext(workspace)
            ));
        });
        runner.start();

        assertThat(bothStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        proceed.countDown();
        runner.join(5000);

        assertThat(resultRef.get()).isNotNull();
        assertThat(resultRef.get().success()).isTrue();
    }

    @Test
    void concurrentToolObservationsPreserveLlmOrder() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch fastCanComplete = new CountDownLatch(1);

        AgentTool slowTool = new AgentTool() {
            @Override
            public String name() { return "slow_tool"; }
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("slow_tool", "Slow tool", "{}");
            }
            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                slowStarted.countDown();
                try {
                    fastCanComplete.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.success(call.id(), "slow-result");
            }
        };

        AgentTool fastTool = new AgentTool() {
            @Override
            public String name() { return "fast_tool"; }
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("fast_tool", "Fast tool", "{}");
            }
            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                try {
                    if (!slowStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        return ToolResult.failure(call.id(), "Timeout waiting for slow tool");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.failure(call.id(), "Interrupted");
                }
                ToolResult result = ToolResult.success(call.id(), "fast-result");
                fastCanComplete.countDown();
                return result;
            }
        };

        ToolRegistry orderedRegistry = new ToolRegistry(List.of(slowTool, fastTool));
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "slow_tool", "{}"),
                ToolCall.of("t2", "fast_tool", "{}")
            ), null),
            new LlmResponse("done", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, orderedRegistry, promptComposer, reporter, sessionService, clock);

        AgentRunResult result = engine.run(
            startRun(3),
            createSession(),
            "Test order",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isTrue();
        List<Message> memory = sessionService.getWorkingMemory("session-1");
        // user + assistant(t1,t2) + observation(t1) + observation(t2) + assistant(final)
        assertThat(memory).hasSize(5);
        Message obs1 = memory.get(2);
        Message obs2 = memory.get(3);
        assertThat(obs1.toolCallId()).isEqualTo("t1");
        assertThat(obs1.content()).isEqualTo("slow-result");
        assertThat(obs2.toolCallId()).isEqualTo("t2");
        assertThat(obs2.content()).isEqualTo("fast-result");
    }

    @Test
    void concurrentToolExecutionWithFailurePreservesOrderAndRecovery() {
        AgentTool failingTool = new AgentTool() {
            @Override
            public String name() { return "edit_file"; }
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("edit_file", "Failing tool", "{}");
            }
            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                return ToolResult.failure(call.id(), "oldText not found in file: src.txt");
            }
        };

        AgentTool okTool = new AgentTool() {
            @Override
            public String name() { return "ok_tool"; }
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("ok_tool", "OK tool", "{}");
            }
            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                return ToolResult.success(call.id(), "ok-result");
            }
        };

        ToolRegistry mixedRegistry = new ToolRegistry(List.of(failingTool, okTool));
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "edit_file", "{}"),
                ToolCall.of("t2", "ok_tool", "{}")
            ), null),
            new LlmResponse("Handled", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, mixedRegistry, promptComposer, reporter, sessionService, clock);

        AgentRunResult result = engine.run(
            startRun(3),
            createSession(),
            "Test mixed",
            new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isFalse();
        List<Message> memory = sessionService.getWorkingMemory("session-1");
        // user + assistant(t1,t2) + observation(t1 with recovery) + observation(t2) + assistant(final)
        assertThat(memory).hasSize(5);
        assertThat(memory.get(2).toolCallId()).isEqualTo("t1");
        assertThat(memory.get(2).content()).contains("oldText not found").contains("[Recovery hint]");
        assertThat(memory.get(3).toolCallId()).isEqualTo("t2");
        assertThat(memory.get(3).content()).isEqualTo("ok-result");
    }

    @Test
    void usesInjectedExecutorForToolExecution() throws Exception {
        java.util.concurrent.ExecutorService customExecutor = java.util.concurrent.Executors.newFixedThreadPool(
            2, r -> new Thread(r, "custom-test-thread")
        );
        java.util.concurrent.atomic.AtomicReference<String> threadName = new java.util.concurrent.atomic.AtomicReference<>();

        AgentTool trackingTool = new AgentTool() {
            @Override
            public String name() { return "tracking_tool"; }
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("tracking_tool", "Tracking tool", "{}");
            }
            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                threadName.set(Thread.currentThread().getName());
                return ToolResult.success(call.id(), "ok");
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(trackingTool));
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "tracking_tool", "{}")
            ), null),
            new LlmResponse("done", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, registry, promptComposer, reporter, sessionService, clock,
            new AgentContextBuilder(promptComposer, new WorkingMemorySelector(), new ContextCompactor()),
            new ToolFailureRecoveryAdvisor(), null,
            new com.tinyclaw.ports.observability.NoOpTraceReporter(), 8, false, customExecutor);

        AgentRunResult result = engine.run(
            startRun(3), createSession(), "Track executor", new ToolExecutionContext(workspace)
        );

        assertThat(result.success()).isTrue();
        assertThat(threadName.get()).startsWith("custom-test-thread");
        customExecutor.shutdown();
    }

    @Test
    void toolExecutionRecordsHaveAccurateTimeWindow() throws Exception {
        CountDownLatch proceed = new CountDownLatch(1);

        // Ticking clock so that startedAt and completedAt are measurably different
        java.util.concurrent.atomic.AtomicLong tick = new java.util.concurrent.atomic.AtomicLong(
            Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        );
        Clock tickingClock = new Clock() {
            @Override
            public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override
            public Clock withZone(ZoneId zone) { return this; }
            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(tick.incrementAndGet());
            }
        };

        AgentTool delayedTool = new AgentTool() {
            @Override
            public String name() { return "delayed_tool"; }
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("delayed_tool", "Delayed tool", "{}");
            }
            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                try {
                    proceed.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.success(call.id(), "delayed-result");
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(delayedTool));
        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "delayed_tool", "{}")
            ), null),
            new LlmResponse("done", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, registry, promptComposer, reporter, sessionService, tickingClock);

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

        java.util.concurrent.atomic.AtomicReference<AgentRunResult> resultRef = new java.util.concurrent.atomic.AtomicReference<>();
        Thread runner = new Thread(() -> {
            resultRef.set(engine.run(
                AgentRun.start("run-1", "session-1", 3, tickingClock.instant()),
                Session.create("session-1", workspace.toAbsolutePath().toString(), tickingClock.instant()),
                "Delayed tool",
                new ToolExecutionContext(workspace), toolRepo
            ));
        });
        runner.start();

        // Let the tool start, then release it
        Thread.sleep(50);
        proceed.countDown();
        runner.join(5000);

        assertThat(resultRef.get()).isNotNull();
        assertThat(resultRef.get().success()).isTrue();
        assertThat(captured).hasSize(1);
        ToolExecutionRecord record = captured.get(0);
        assertThat(record.startedAt()).isNotNull();
        assertThat(record.completedAt()).isNotNull();
        assertThat(record.completedAt()).isAfter(record.startedAt());
    }

    @Test
    void toolExceptionConvertedToFailureWithoutBreakingOthers() {
        ToolRegistry explodingRegistry = new ToolRegistry(List.of()) {
            @Override
            public ToolResult execute(ToolCall call, ToolExecutionContext context) {
                if ("explode".equals(call.name())) {
                    throw new RuntimeException("boom");
                }
                return ToolResult.success(call.id(), "ok-result");
            }
            @Override
            public List<ToolDefinition> availableTools() {
                return List.of(
                    new ToolDefinition("explode", "Exploding tool", "{}"),
                    new ToolDefinition("ok_tool", "OK tool", "{}")
                );
            }
        };

        FakeLlmGateway fakeLlm = new FakeLlmGateway(List.of(
            new LlmResponse("", List.of(
                ToolCall.of("t1", "explode", "{}"),
                ToolCall.of("t2", "ok_tool", "{}")
            ), null),
            new LlmResponse("Handled", List.of(), null)
        ));
        AgentEngine engine = new AgentEngine(fakeLlm, explodingRegistry, promptComposer, reporter, sessionService, clock);

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
            startRun(3), createSession(), "Test exception",
            new ToolExecutionContext(workspace), toolRepo
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorReason()).contains("explode").contains("failed");

        List<Message> memory = sessionService.getWorkingMemory("session-1");
        // user + assistant(t1,t2) + observation(t1) + observation(t2) + assistant(final)
        assertThat(memory).hasSize(5);
        assertThat(memory.get(2).toolCallId()).isEqualTo("t1");
        assertThat(memory.get(2).content()).contains("boom");
        assertThat(memory.get(3).toolCallId()).isEqualTo("t2");
        assertThat(memory.get(3).content()).isEqualTo("ok-result");

        assertThat(captured).hasSize(2);
        ToolExecutionRecord r1 = captured.stream().filter(r -> r.stepId().equals("t1")).findFirst().orElseThrow();
        ToolExecutionRecord r2 = captured.stream().filter(r -> r.stepId().equals("t2")).findFirst().orElseThrow();
        assertThat(r1.isError()).isTrue();
        assertThat(r1.output()).contains("boom");
        assertThat(r2.isError()).isFalse();
        assertThat(r2.output()).isEqualTo("ok-result");
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
