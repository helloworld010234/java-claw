package com.tinyclaw.adapters.cli;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.run.AgentRunStatus;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.persistence.AgentMessageDto;
import com.tinyclaw.ports.persistence.AgentRunSummary;
import com.tinyclaw.ports.persistence.MessageRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.ToolExecutionRecord;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.persistence.UsageRecord;
import com.tinyclaw.ports.persistence.UsageRepositoryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ShowRunCommandTest {

    private static final Instant NOW = Instant.parse("2026-06-10T00:00:00Z");

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUpStreams() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void missingRunRepositoryReturnsUsageError() {
        ShowRunCommand command = new ShowRunCommand(null, null, null, null);

        int exitCode = new CommandLine(command).execute("--run-id", "run-1");

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("Run repository not available");
    }

    @Test
    void unknownRunReturnsUsageError() {
        ShowRunCommand command = new ShowRunCommand(new StubRunRepository(Optional.empty()), null, null, null);

        int exitCode = new CommandLine(command).execute("--run-id", "missing");

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).contains("Run not found: missing");
    }

    @Test
    void summaryUsesFallbacksWhenOptionalRepositoriesAreAbsent() {
        AgentRunSummary run = new AgentRunSummary(
            "run-1", "sess-1", null, AgentRunStatus.WAITING_APPROVAL, 4,
            "prompt", null, NOW, null
        );
        ShowRunCommand command = new ShowRunCommand(new StubRunRepository(Optional.of(run)), null, null, null);

        int exitCode = new CommandLine(command).execute("--run-id", "run-1");

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("runId: run-1");
        assertThat(output).contains("mode: unknown");
        assertThat(output).contains("status: waiting_approval");
        assertThat(output).contains("messages: 0");
        assertThat(output).contains("toolExecutions: 0");
        assertThat(output).contains("usage: none");
        assertThat(output).contains("error: ");
    }

    @Test
    void detailPrintsMessagesToolExecutionsAndUsageTotals() {
        AgentRunSummary run = new AgentRunSummary(
            "run-2", "sess-2", "cli", AgentRunStatus.FAILED, 2,
            "prompt", "boom", NOW, NOW
        );
        List<AgentMessageDto> messages = List.of(
            message(Role.SYSTEM, "system note", null, null, 1),
            message(Role.USER, "hello", null, null, 2),
            message(Role.ASSISTANT, "plain answer", null, null, 3),
            message(Role.ASSISTANT, "needs tool", "[{\"id\":\"tc-1\",\"name\":\"read_file\",\"argumentsJson\":\"{}\"}]", null, 4),
            message(Role.USER, "tool output", null, "tc-1", 5)
        );
        List<ToolExecutionRecord> tools = List.of(new ToolExecutionRecord(
            "te-1", "run-2", "sess-2", "tc-1", "read_file",
            "{\"path\":\"README.md\"}", "contents", false, NOW, NOW
        ));
        List<UsageRecord> usage = List.of(
            new UsageRecord("run-2", "sess-2", "model-a", 10, 3, 0.01, true, NOW),
            new UsageRecord("run-2", "sess-2", "model-a", 5, 2, 0.02, true, NOW.plusSeconds(1))
        );
        ShowRunCommand command = new ShowRunCommand(
            new StubRunRepository(Optional.of(run)),
            new StubMessageRepository(messages),
            new StubToolExecutionRepository(tools),
            new StubUsageRepository(usage)
        );

        int exitCode = new CommandLine(command).execute("--run-id", "run-2", "--detail");

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("status: failed");
        assertThat(output).contains("usage: 15 prompt / 5 completion tokens");
        assertThat(output).contains("error: boom");
        assertThat(output).contains("messages:");
        assertThat(output).contains("[1] system: system note");
        assertThat(output).contains("[2] user: hello");
        assertThat(output).contains("[3] assistant: plain answer");
        assertThat(output).contains("[4] assistant (tool_calls): needs tool");
        assertThat(output).contains("[5] tool_observation (tc-1): tool output");
        assertThat(output).contains("toolExecutions:");
        assertThat(output).contains("read_file (tc-1)");
        assertThat(output).contains("arguments: {\"path\":\"README.md\"}");
        assertThat(output).contains("result: contents");
        assertThat(output).contains("error: false");
        assertThat(output).contains("turn 1: 10 prompt / 3 completion tokens");
        assertThat(output).contains("turn 2: 5 prompt / 2 completion tokens");
        assertThat(output).contains("total: 15 prompt / 5 completion tokens");
    }

    @Test
    void completedRunPrintsSuccessStatus() {
        AgentRunSummary run = new AgentRunSummary(
            "run-3", "sess-3", "web", AgentRunStatus.COMPLETED, 1,
            "prompt", null, NOW, NOW
        );
        ShowRunCommand command = new ShowRunCommand(
            new StubRunRepository(Optional.of(run)),
            new StubMessageRepository(List.of()),
            new StubToolExecutionRepository(List.of()),
            new StubUsageRepository(List.of())
        );

        int exitCode = new CommandLine(command).execute("--run-id", "run-3", "--detail");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("status: success").contains("usage: none");
    }

    private static AgentMessageDto message(Role role, String content, String toolCallsJson,
                                           String toolCallId, int sequenceNumber) {
        return new AgentMessageDto(
            "msg-" + sequenceNumber, "run-2", "sess-2", role, content,
            toolCallsJson, toolCallId, sequenceNumber, NOW.plusMillis(sequenceNumber)
        );
    }

    private record StubRunRepository(Optional<AgentRunSummary> run) implements RunRepositoryPort {
        @Override
        public void saveSession(Session session) {
        }

        @Override
        public Optional<Session> findSessionById(String sessionId) {
            return Optional.empty();
        }

        @Override
        public void saveRunStarted(AgentRun run, String mode, String prompt) {
        }

        @Override
        public void saveRunCompleted(AgentRun run) {
        }

        @Override
        public void saveRunCompleted(String runId, int turnCount, Instant completedAt) {
        }

        @Override
        public void saveRunFailed(AgentRun run, String reason) {
        }

        @Override
        public void saveRunFailed(String runId, int turnCount, String reason, Instant completedAt) {
        }

        @Override
        public Optional<AgentRunSummary> findById(String runId) {
            return run.filter(r -> r.id().equals(runId));
        }
    }

    private record StubMessageRepository(List<AgentMessageDto> messages) implements MessageRepositoryPort {
        @Override
        public void append(String runId, String sessionId, Message message) {
        }

        @Override
        public List<AgentMessageDto> findByRunId(String runId) {
            return messages;
        }

        @Override
        public List<AgentMessageDto> findBySessionId(String sessionId, int limit) {
            return List.of();
        }
    }

    private record StubToolExecutionRepository(List<ToolExecutionRecord> records)
        implements ToolExecutionRepositoryPort {

        @Override
        public void append(String runId, ToolExecutionRecord record) {
        }

        @Override
        public List<ToolExecutionRecord> findByRunId(String runId) {
            return records;
        }
    }

    private record StubUsageRepository(List<UsageRecord> records) implements UsageRepositoryPort {
        @Override
        public void save(UsageRecord record) {
        }

        @Override
        public List<UsageRecord> findByRunId(String runId) {
            return records;
        }
    }
}
