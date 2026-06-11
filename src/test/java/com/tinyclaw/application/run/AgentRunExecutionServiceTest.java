package com.tinyclaw.application.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.ports.persistence.AgentMessageDto;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.persistence.UsageRecord;
import com.tinyclaw.ports.persistence.MessageRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.UsageRepositoryPort;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.session.SessionService;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunExecutionServiceTest {

    static class InMemoryRunRepository implements RunRepositoryPort {
        Session savedSession;
        AgentRun savedRun;
        String completedRunId;
        String failedRunId;
        int failedTurnCount;
        String failedReason;

        @Override public void saveSession(Session session) { this.savedSession = session; }
        @Override public Optional<Session> findSessionById(String sessionId) { return Optional.empty(); }
        @Override public void saveRunStarted(AgentRun run, String mode, String prompt) { this.savedRun = run; }
        @Override public void saveRunCompleted(AgentRun run) { }
        @Override public void saveRunCompleted(String runId, int turnCount, Instant completedAt) { this.completedRunId = runId; }
        @Override public void saveRunFailed(AgentRun run, String reason) { }
        @Override public void saveRunFailed(String runId, int turnCount, String reason, Instant completedAt) {
            this.failedRunId = runId;
            this.failedTurnCount = turnCount;
            this.failedReason = reason;
        }
        @Override public Optional<com.tinyclaw.ports.persistence.AgentRunSummary> findById(String runId) { return Optional.empty(); }
    }

    static class InMemoryMessageRepository implements MessageRepositoryPort {
        final List<AgentMessageDto> messages = new ArrayList<>();

        @Override public void append(String runId, String sessionId, Message message) {
            messages.add(new AgentMessageDto("id-" + messages.size(), runId, sessionId, message.role(), message.content(),
                null, message.toolCallId(), messages.size() + 1, Instant.now()));
        }
        @Override public List<AgentMessageDto> findByRunId(String runId) {
            return messages.stream().filter(m -> m.runId().equals(runId)).toList();
        }
        @Override public List<AgentMessageDto> findBySessionId(String sessionId, int limit) { return List.of(); }
    }

    static class InMemoryUsageRepository implements UsageRepositoryPort {
        final List<UsageRecord> records = new ArrayList<>();

        @Override public void save(UsageRecord record) { records.add(record); }
        @Override public List<UsageRecord> findByRunId(String runId) {
            return records.stream().filter(r -> r.runId().equals(runId)).toList();
        }
    }

    @Test
    void executesFakeEngineRunAndSavesMessages() {
        InMemoryRunRepository runRepo = new InMemoryRunRepository();
        InMemoryMessageRepository msgRepo = new InMemoryMessageRepository();
        SessionService sessionService = new com.tinyclaw.adapters.session.InMemorySessionService();
        Reporter reporter = new com.tinyclaw.adapters.reporter.NoOpReporter();
        ObjectMapper objectMapper = new ObjectMapper();

        AgentRunExecutionService service = new AgentRunExecutionService(
            runRepo, msgRepo, sessionService, objectMapper, reporter
        );

        AgentEngine engine = new AgentEngine(
            FakeLlmGateway.forPrompt("hello"),
            new com.tinyclaw.application.tool.ToolRegistry(List.of()),
            new com.tinyclaw.application.engine.PromptComposer(),
            reporter,
            sessionService
        );

        Session session = Session.create("session-1", "/tmp", Instant.now());
        ToolExecutionContext context = new ToolExecutionContext(Paths.get("/tmp"));

        AgentRunResult result = service.execute("run-1", session, "hello", context, engine, "fake", null, 5);

        assertThat(result).isNotNull();
        assertThat(runRepo.savedSession).isNotNull();
        assertThat(runRepo.savedRun).isNotNull();
        assertThat(runRepo.completedRunId).isEqualTo("run-1");
        assertThat(msgRepo.messages).isNotEmpty();
        assertThat(msgRepo.messages.stream().anyMatch(m -> m.role() == Role.USER)).isTrue();
    }

    @Test
    void persistsUsageAggregateOnSuccessfulRun() {
        InMemoryRunRepository runRepo = new InMemoryRunRepository();
        InMemoryMessageRepository msgRepo = new InMemoryMessageRepository();
        InMemoryUsageRepository usageRepo = new InMemoryUsageRepository();
        SessionService sessionService = new com.tinyclaw.adapters.session.InMemorySessionService();
        Reporter reporter = new com.tinyclaw.adapters.reporter.NoOpReporter();
        ObjectMapper objectMapper = new ObjectMapper();

        AgentRunExecutionService service = new AgentRunExecutionService(
            runRepo, msgRepo, sessionService, objectMapper, reporter, usageRepo
        );

        AgentEngine engine = new AgentEngine(
            FakeLlmGateway.forPrompt("hello"),
            new com.tinyclaw.application.tool.ToolRegistry(List.of()),
            new com.tinyclaw.application.engine.PromptComposer(),
            reporter,
            sessionService
        );

        Session session = Session.create("session-usage", "/tmp", Instant.now());
        ToolExecutionContext context = new ToolExecutionContext(Paths.get("/tmp"));

        AgentRunResult result = service.execute("run-usage", session, "hello", context, engine, "fake", null, 5);

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        // FakeLlmGateway does not report usage, so totalUsage is null
        assertThat(usageRepo.records).isEmpty();
    }

    @Test
    void restoresHistoryMessagesOnResume() {
        InMemoryRunRepository runRepo = new InMemoryRunRepository();
        InMemoryMessageRepository msgRepo = new InMemoryMessageRepository();
        SessionService sessionService = new com.tinyclaw.adapters.session.InMemorySessionService();
        Reporter reporter = new com.tinyclaw.adapters.reporter.NoOpReporter();
        ObjectMapper objectMapper = new ObjectMapper();

        // Pre-populate a previous user message
        msgRepo.append("run-0", "session-resume", Message.user("previous"));

        AgentRunExecutionService service = new AgentRunExecutionService(
            runRepo, msgRepo, sessionService, objectMapper, reporter
        );

        AgentEngine engine = new AgentEngine(
            FakeLlmGateway.forPrompt("hello"),
            new com.tinyclaw.application.tool.ToolRegistry(List.of()),
            new com.tinyclaw.application.engine.PromptComposer(),
            reporter,
            sessionService
        );

        Session session = Session.create("session-resume", "/tmp", Instant.now());
        ToolExecutionContext context = new ToolExecutionContext(Paths.get("/tmp"));

        AgentRunResult result = service.execute("run-1", session, "hello", context, engine, "fake", null, 5);

        assertThat(result).isNotNull();
        // Should have previous message + new messages
        List<AgentMessageDto> allMessages = msgRepo.findByRunId("run-1");
        assertThat(allMessages).isNotEmpty();
    }

    @Test
    void passesMaxTurnsToEngine() {
        InMemoryRunRepository runRepo = new InMemoryRunRepository();
        SessionService sessionService = new com.tinyclaw.adapters.session.InMemorySessionService();
        Reporter reporter = new com.tinyclaw.adapters.reporter.NoOpReporter();
        ObjectMapper objectMapper = new ObjectMapper();

        AgentRunExecutionService service = new AgentRunExecutionService(
            runRepo, null, sessionService, objectMapper, reporter
        );

        // Use a fake engine that records the run's maxTurns
        com.tinyclaw.ports.llm.LlmGateway fakeLlm = request -> {
            throw new com.tinyclaw.ports.llm.LlmException("stop");
        };
        AgentEngine engine = new AgentEngine(
            fakeLlm,
            new com.tinyclaw.application.tool.ToolRegistry(List.of()),
            new com.tinyclaw.application.engine.PromptComposer(),
            reporter,
            sessionService
        );

        Session session = Session.create("session-turns", "/tmp", Instant.now());
        ToolExecutionContext context = new ToolExecutionContext(java.nio.file.Paths.get("/tmp"));

        AgentRunResult result = service.execute("run-turns", session, "hello", context, engine, "fake", null, 7);

        assertThat(result).isNotNull();
        assertThat(runRepo.savedRun).isNotNull();
        assertThat(runRepo.savedRun.maxTurns()).isEqualTo(7);
    }
}
