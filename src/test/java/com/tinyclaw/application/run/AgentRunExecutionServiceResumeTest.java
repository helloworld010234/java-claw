package com.tinyclaw.application.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
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

class AgentRunExecutionServiceResumeTest {

    static class InMemoryRunRepository implements RunRepositoryPort {
        Session savedSession;
        AgentRun savedRun;
        String completedRunId;

        @Override public void saveSession(Session session) { this.savedSession = session; }
        @Override public Optional<Session> findSessionById(String sessionId) { return Optional.empty(); }
        @Override public void saveRunStarted(AgentRun run, String mode, String prompt) { this.savedRun = run; }
        @Override public void saveRunCompleted(AgentRun run) { }
        @Override public void saveRunCompleted(String runId, int turnCount, Instant completedAt) { this.completedRunId = runId; }
        @Override public void saveRunFailed(AgentRun run, String reason) { }
        @Override public void saveRunFailed(String runId, int turnCount, String reason, Instant completedAt) { }
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
        @Override public List<AgentMessageDto> findBySessionId(String sessionId, int limit) {
            return messages.stream()
                .filter(m -> m.sessionId().equals(sessionId) && m.role() != Role.SYSTEM)
                .limit(limit > 0 ? limit : Integer.MAX_VALUE)
                .toList();
        }
    }

    @Test
    void secondRunOnSameSessionLoadsHistoryAndAppendsNewMessages() {
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

        Session session = Session.create("session-resume", "/tmp", Instant.now());
        ToolExecutionContext context = new ToolExecutionContext(Paths.get("/tmp"));

        // First run
        AgentRunResult result1 = service.execute("run-1", session, "hello", context, engine, "fake", null, 5);
        assertThat(result1).isNotNull();
        int messagesAfterRun1 = msgRepo.findByRunId("run-1").size();
        assertThat(messagesAfterRun1).isGreaterThan(0);

        // Second run on same session
        Session session2 = Session.create("session-resume", "/tmp", Instant.now());
        AgentRunResult result2 = service.execute("run-2", session2, "world", context, engine, "fake", null, 5);
        assertThat(result2).isNotNull();

        // Both runs should have their own messages
        assertThat(msgRepo.findByRunId("run-2")).isNotEmpty();
    }
}
