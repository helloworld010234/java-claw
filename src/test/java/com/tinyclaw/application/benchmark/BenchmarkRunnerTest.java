package com.tinyclaw.application.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.benchmark.BenchmarkFakeLlmFactory;
import com.tinyclaw.adapters.reporter.NoOpReporter;
import com.tinyclaw.adapters.session.InMemorySessionService;
import com.tinyclaw.adapters.tools.filesystem.EditFileTool;
import com.tinyclaw.adapters.tools.filesystem.ReadFileTool;
import com.tinyclaw.adapters.tools.filesystem.WriteFileTool;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.application.engine.PromptComposer;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.application.tool.AllowAllPolicy;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import com.tinyclaw.ports.persistence.AgentRunSummary;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkRunnerTest {

    @TempDir
    Path tempDir;

    private BenchmarkRunner runner;
    private InMemorySessionService sessionService;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry(
            List.of(new ReadFileTool(), new WriteFileTool(), new EditFileTool()),
            List.of(new AllowAllPolicy())
        );
        sessionService = new InMemorySessionService();
        LlmGateway dummyLlm = request -> new LlmResponse("", List.of(), null);
        AgentEngine engine = new AgentEngine(
            dummyLlm, registry, new PromptComposer(), new NoOpReporter(), sessionService
        );
        runner = new BenchmarkRunner(
            new AgentRunExecutionService(null, null, sessionService, new ObjectMapper(), new NoOpReporter()),
            engine,
            20
        );
    }

    @Test
    void editConfigCasePasses() throws Exception {
        BenchmarkCase caseDef = BenchmarkSuite.editConfigCase();
        BenchmarkResult result = runner.run(caseDef, tempDir, BenchmarkFakeLlmFactory.forCase(caseDef));

        assertThat(result.passed()).isTrue();
        assertThat(result.caseId()).isEqualTo(BenchmarkSuite.EDIT_CONFIG_CASE_ID);
        assertThat(result.turnCount()).isGreaterThan(0);
        assertThat(result.durationMillis()).isNotNull().isPositive();
        assertThat(Files.readString(result.workspace().resolve("config.json")))
            .contains("\"version\": \"v2.0.0\"");
    }

    @Test
    void writeMathTestCasePasses() throws Exception {
        BenchmarkCase caseDef = BenchmarkSuite.writeMathTestCase();
        BenchmarkResult result = runner.run(caseDef, tempDir, BenchmarkFakeLlmFactory.forCase(caseDef));

        assertThat(result.passed()).isTrue();
        assertThat(result.caseId()).isEqualTo(BenchmarkSuite.WRITE_TEST_CASE_ID);
        assertThat(result.turnCount()).isGreaterThan(0);
        assertThat(result.durationMillis()).isNotNull().isPositive();
        assertThat(result.workspace().resolve("math_test.go")).exists();
    }

    @Test
    void failedValidationReportsFailure() {
        BenchmarkCase caseDef = new BenchmarkCase(
            "bad-case",
            "Bad case",
            "prompt",
            workspace -> {
                try {
                    Files.writeString(workspace.resolve("x.txt"), "x");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            },
            workspace -> {
                throw new RuntimeException("expected validation failure");
            }
        );
        BenchmarkResult result = runner.run(caseDef, tempDir, request -> new LlmResponse("Done", List.of(), null));

        assertThat(result.passed()).isFalse();
        assertThat(result.errorReason()).contains("Validation failed");
        assertThat(result.durationMillis()).isNotNull().isPositive();
    }

    @Test
    void executionPersistsMessages() {
        BenchmarkCase caseDef = BenchmarkSuite.editConfigCase();
        BenchmarkResult result = runner.run(caseDef, tempDir, BenchmarkFakeLlmFactory.forCase(caseDef));

        assertThat(result.passed()).isTrue();
        List<Message> messages = sessionService.getWorkingMemory(result.sessionId());
        assertThat(messages).isNotEmpty();
        assertThat(messages.stream().anyMatch(m -> m.role() == Role.USER)).isTrue();
        assertThat(messages.stream().anyMatch(m -> m.role() == Role.ASSISTANT)).isTrue();
    }

    @Test
    void engineTypeIsPersistedToRunRepository() {
        BenchmarkCase caseDef = BenchmarkSuite.editConfigCase();
        com.tinyclaw.adapters.llm.fake.FakeLlmGateway fakeGateway = BenchmarkFakeLlmFactory.forCase(caseDef);
        LlmGateway trackingGateway = request -> {
            fakeGateway.recordedRequests();
            return fakeGateway.generate(request);
        };

        AtomicReference<String> persistedEngineType = new AtomicReference<>();
        RunRepositoryPort recordingRepository = new RunRepositoryPort() {
            @Override
            public void saveSession(Session session) { }

            @Override
            public Optional<Session> findSessionById(String sessionId) { return Optional.empty(); }

            @Override
            public void saveRunStarted(AgentRun run, String mode, String prompt) {
                persistedEngineType.set(mode);
            }

            @Override
            public void saveRunCompleted(AgentRun run) { }

            @Override
            public void saveRunCompleted(String runId, int turnCount, Instant completedAt) { }

            @Override
            public void saveRunFailed(AgentRun run, String reason) { }

            @Override
            public void saveRunFailed(String runId, int turnCount, String reason, Instant completedAt) { }

            @Override
            public Optional<AgentRunSummary> findById(String runId) { return Optional.empty(); }
        };
        AgentRunExecutionService executionService = new AgentRunExecutionService(
            recordingRepository, null, sessionService, new ObjectMapper(), new NoOpReporter()
        );
        BenchmarkRunner typedRunner = new BenchmarkRunner(executionService, createEngine(), 20);

        BenchmarkResult result = typedRunner.run(caseDef, tempDir, trackingGateway, "custom-engine");

        assertThat(result.passed()).isTrue();
        assertThat(persistedEngineType.get()).isEqualTo("custom-engine");
    }

    @Test
    void usageFromAgentRunResultIsCaptured() {
        BenchmarkCase caseDef = BenchmarkSuite.editConfigCase();
        com.tinyclaw.adapters.llm.fake.FakeLlmGateway fakeGateway = BenchmarkFakeLlmFactory.forCase(caseDef);
        LlmGateway usageGateway = request -> {
            LlmResponse base = fakeGateway.generate(request);
            if (base.hasToolCalls()) {
                return new LlmResponse(base.content(), base.toolCalls(), new Usage(10, 5));
            }
            return new LlmResponse(base.content(), base.toolCalls(), new Usage(3, 7));
        };

        BenchmarkResult result = runner.run(caseDef, tempDir, usageGateway);

        assertThat(result.passed()).isTrue();
        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().promptTokens()).isPositive();
        assertThat(result.usage().completionTokens()).isPositive();
    }

    @Test
    void agentRunFailureRetainsTurnCountAndUsage() {
        BenchmarkCase caseDef = BenchmarkSuite.editConfigCase();
        AgentRunExecutionService failingService = new AgentRunExecutionService(
            null, null, sessionService, new ObjectMapper(), new NoOpReporter()
        ) {
            @Override
            public AgentRunResult execute(String runId, Session session, String prompt,
                                          com.tinyclaw.ports.tool.ToolExecutionContext context,
                                          AgentEngine engine, String engineType,
                                          com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort toolRepo,
                                          int maxTurns) {
                return new AgentRunResult(false, "boom", 7, "forced failure", new Usage(12, 8));
            }
        };
        BenchmarkRunner failingRunner = new BenchmarkRunner(failingService, createEngine(), 20);

        BenchmarkResult result = failingRunner.run(caseDef, tempDir, request -> new LlmResponse("", List.of(), null));

        assertThat(result.passed()).isFalse();
        assertThat(result.turnCount()).isEqualTo(7);
        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().promptTokens()).isEqualTo(12);
        assertThat(result.usage().completionTokens()).isEqualTo(8);
        assertThat(result.errorReason()).contains("forced failure");
    }

    @Test
    void validationFailureRetainsTurnCountAndUsage() {
        BenchmarkCase caseDef = new BenchmarkCase(
            "validation-fails",
            "Validation fails",
            "prompt",
            workspace -> { },
            workspace -> {
                throw new RuntimeException("validation boom");
            }
        );
        AgentRunExecutionService successService = new AgentRunExecutionService(
            null, null, sessionService, new ObjectMapper(), new NoOpReporter()
        ) {
            @Override
            public AgentRunResult execute(String runId, Session session, String prompt,
                                          com.tinyclaw.ports.tool.ToolExecutionContext context,
                                          AgentEngine engine, String engineType,
                                          com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort toolRepo,
                                          int maxTurns) {
                return new AgentRunResult(true, "ok", 4, null, new Usage(5, 6));
            }
        };
        BenchmarkRunner successRunner = new BenchmarkRunner(successService, createEngine(), 20);

        BenchmarkResult result = successRunner.run(caseDef, tempDir, request -> new LlmResponse("", List.of(), null));

        assertThat(result.passed()).isFalse();
        assertThat(result.turnCount()).isEqualTo(4);
        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().promptTokens()).isEqualTo(5);
        assertThat(result.usage().completionTokens()).isEqualTo(6);
        assertThat(result.errorReason()).contains("validation boom");
    }

    private AgentEngine createEngine() {
        ToolRegistry registry = new ToolRegistry(
            List.of(new ReadFileTool(), new WriteFileTool(), new EditFileTool()),
            List.of(new AllowAllPolicy())
        );
        LlmGateway dummyLlm = request -> new LlmResponse("", List.of(), null);
        return new AgentEngine(dummyLlm, registry, new PromptComposer(), new NoOpReporter(), sessionService);
    }
}
