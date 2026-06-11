package com.tinyclaw.application.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.application.persistence.AgentMessageDto;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.persistence.MessageRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
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

class AgentRunExecutionServiceFailureTest {

    static class InMemoryRunRepository implements RunRepositoryPort {
        Session savedSession;
        AgentRun savedRun;
        String failedRunId;
        int failedTurnCount;
        String failedReason;

        @Override public void saveSession(Session session) { this.savedSession = session; }
        @Override public Optional<Session> findSessionById(String sessionId) { return Optional.empty(); }
        @Override public void saveRunStarted(AgentRun run, String mode, String prompt) { this.savedRun = run; }
        @Override public void saveRunCompleted(AgentRun run) { }
        @Override public void saveRunCompleted(String runId, int turnCount, Instant completedAt) { }
        @Override public void saveRunFailed(AgentRun run, String reason) { }
        @Override public void saveRunFailed(String runId, int turnCount, String reason, Instant completedAt) {
            this.failedRunId = runId;
            this.failedTurnCount = turnCount;
            this.failedReason = reason;
        }
        @Override public Optional<com.tinyclaw.application.persistence.AgentRunSummary> findById(String runId) { return Optional.empty(); }
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

    @Test
    void persistsMessagesEvenWhenEngineThrows() {
        InMemoryRunRepository runRepo = new InMemoryRunRepository();
        InMemoryMessageRepository msgRepo = new InMemoryMessageRepository();
        SessionService sessionService = new com.tinyclaw.adapters.session.InMemorySessionService();
        Reporter reporter = new com.tinyclaw.adapters.reporter.NoOpReporter();
        ObjectMapper objectMapper = new ObjectMapper();

        AgentRunExecutionService service = new AgentRunExecutionService(
            runRepo, msgRepo, sessionService, objectMapper, reporter
        );

        AgentEngine failingEngine = new AgentEngine(
            request -> { throw new RuntimeException("LLM down"); },
            new com.tinyclaw.application.tool.ToolRegistry(List.of()),
            new com.tinyclaw.application.engine.PromptComposer(),
            reporter,
            sessionService
        );

        Session session = Session.create("session-1", "/tmp", Instant.now());
        ToolExecutionContext context = new ToolExecutionContext(Paths.get("/tmp"));

        AgentRunResult result = service.execute("run-1", session, "hello", context, failingEngine, "fake", null, 5);

        // Engine catches LLM exception and returns failed result
        assertThat(result.success()).isFalse();
        assertThat(result.errorReason()).contains("LLM down");

        // Messages should still be persisted (at least the user prompt)
        assertThat(msgRepo.messages).isNotEmpty();
        assertThat(msgRepo.messages.stream().anyMatch(m -> m.role() == Role.USER)).isTrue();

        // Run should be marked as failed
        assertThat(runRepo.failedRunId).isEqualTo("run-1");
        assertThat(runRepo.failedReason).contains("LLM down");
    }
}
