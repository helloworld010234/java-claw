package com.tinyclaw.application.approval;

import com.tinyclaw.application.persistence.AgentRunSummary;
import com.tinyclaw.application.persistence.ToolExecutionRecord;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.ToolResult;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.run.AgentRunStatus;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.domain.session.SessionStatus;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.tool.AgentTool;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalResumeServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-10T00:00:00Z"), ZoneOffset.UTC);

    private final InMemoryApprovalRepository approvalRepository = new InMemoryApprovalRepository();
    private final InMemoryRunRepository runRepository = new InMemoryRunRepository();
    private final InMemoryToolExecutionRepository toolExecutionRepository = new InMemoryToolExecutionRepository();

    @Test
    void approvedApprovalCanResumeAndCreatesNewAudit() {
        AgentTool echoTool = new AgentToolStub("shell_command", ToolResult.success("tc-1", "resumed-ok"));
        ToolRegistry registry = new ToolRegistry(List.of(echoTool));
        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository, registry, CLOCK
        );
        seedApprovedApprovalAndRun("apr-1", "run-1", "sess-1", "tc-1", "shell_command", "{\"command\":\"echo hi\"}");

        ApprovalResumeResult result = service.resume("apr-1");

        assertThat(result.resumed()).isTrue();
        assertThat(result.toolError()).isFalse();
        assertThat(result.runStatus()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.output()).isEqualTo("resumed-ok");
        assertThat(toolExecutionRepository.findByRunId("run-1")).hasSize(2);
        assertThat(runRepository.findById("run-1").orElseThrow().status())
            .isEqualTo(AgentRunStatus.COMPLETED);
    }

    @Test
    void pendingApprovalCannotResume() {
        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository, new ToolRegistry(List.of()), CLOCK
        );
        seedApproval("apr-pending", "run-1", "sess-1", "tc-1", "shell_command", ApprovalStatus.PENDING);

        ApprovalResumeResult result = service.resume("apr-pending");

        assertThat(result.resumed()).isFalse();
        assertThat(result.toolError()).isTrue();
        assertThat(result.output()).contains("not approved").contains("PENDING");
        assertThat(runRepository.findById("run-1").orElseThrow().status())
            .isEqualTo(AgentRunStatus.FAILED);
    }

    @Test
    void rejectedApprovalCannotResume() {
        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository, new ToolRegistry(List.of()), CLOCK
        );
        seedApproval("apr-rejected", "run-1", "sess-1", "tc-1", "shell_command", ApprovalStatus.REJECTED);

        ApprovalResumeResult result = service.resume("apr-rejected");

        assertThat(result.resumed()).isFalse();
        assertThat(result.toolError()).isTrue();
        assertThat(result.output()).contains("not approved").contains("REJECTED");
    }

    @Test
    void missingApprovalReturnsFailure() {
        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository, new ToolRegistry(List.of()), CLOCK
        );

        ApprovalResumeResult result = service.resume("missing");

        assertThat(result.resumed()).isFalse();
        assertThat(result.toolError()).isTrue();
        assertThat(result.output()).contains("not found");
    }

    @Test
    void missingRunReturnsFailure() {
        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository, new ToolRegistry(List.of()), CLOCK
        );
        ApprovalRequest pending = ApprovalRequest.pending(
            "apr-run-missing", "run-missing", "sess-1", "tc-1", "shell_command", "args", CLOCK.instant()
        );
        approvalRepository.save(pending);
        approvalRepository.update(pending.approve("ok", CLOCK.instant()));

        ApprovalResumeResult result = service.resume("apr-run-missing");

        assertThat(result.resumed()).isFalse();
        assertThat(result.toolError()).isTrue();
        assertThat(result.output()).contains("Run not found");
    }

    @Test
    void missingOriginalToolExecutionReturnsFailure() {
        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository, new ToolRegistry(List.of()), CLOCK
        );
        seedApprovedApprovalAndRun("apr-no-exec", "run-1", "sess-1", "tc-1", "shell_command", "{}");
        // Do not append any tool execution record
        toolExecutionRepository.clear();

        ApprovalResumeResult result = service.resume("apr-no-exec");

        assertThat(result.resumed()).isFalse();
        assertThat(result.toolError()).isTrue();
        assertThat(result.output()).contains("Original tool execution not found");
    }

    @Test
    void toolExecutionFailureKeepsRunFailedAndAppendsAudit() {
        AgentTool failingTool = new AgentToolStub("shell_command", ToolResult.failure("tc-1", "boom"));
        ToolRegistry registry = new ToolRegistry(List.of(failingTool));
        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository, registry, CLOCK
        );
        seedApprovedApprovalAndRun("apr-fail", "run-1", "sess-1", "tc-1", "shell_command", "{\"command\":\"false\"}");

        ApprovalResumeResult result = service.resume("apr-fail");

        assertThat(result.resumed()).isTrue();
        assertThat(result.toolError()).isTrue();
        assertThat(result.runStatus()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(toolExecutionRepository.findByRunId("run-1")).hasSize(2);
        AgentRunSummary run = runRepository.findById("run-1").orElseThrow();
        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.errorReason()).contains("Resume failed");
    }

    @Test
    void dangerousCommandStillBlockedWhenApprovalIsApproved() {
        AgentTool shellTool = new AgentToolStub("shell_command", ToolResult.success("tc-1", "should-not-run"));
        ToolRegistry registry = new ToolRegistry(List.of(shellTool), List.of(
            new com.tinyclaw.application.tool.DangerousCommandPolicy()
        ));
        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository, registry, CLOCK
        );
        seedApprovedApprovalAndRun("apr-danger", "run-1", "sess-1", "tc-1", "shell_command",
            "{\"command\":\"rm -rf /\"}");

        ApprovalResumeResult result = service.resume("apr-danger");

        assertThat(result.resumed()).isTrue();
        assertThat(result.toolError()).isTrue();
        assertThat(result.output()).contains("Dangerous command blocked");
        assertThat(toolExecutionRepository.findByRunId("run-1")).hasSize(2);
    }

    @Test
    void resumeReusesOriginalToolNameAndArguments() {
        SpyTool spyTool = new SpyTool("shell_command", ToolResult.success("tc-1", "ok"));
        ToolRegistry registry = new ToolRegistry(List.of(spyTool));
        ApprovalResumeService service = new ApprovalResumeService(
            approvalRepository, runRepository, toolExecutionRepository, registry, CLOCK
        );
        String args = "{\"command\":\"echo resumed-ok\"}";
        seedApprovedApprovalAndRun("apr-args", "run-1", "sess-1", "tc-1", "shell_command", args);

        service.resume("apr-args");

        assertThat(spyTool.lastCall).isNotNull();
        assertThat(spyTool.lastCall.name()).isEqualTo("shell_command");
        assertThat(spyTool.lastCall.argumentsJson()).isEqualTo(args);
    }

    private void seedApprovedApprovalAndRun(String approvalId, String runId, String sessionId,
                                            String toolCallId, String toolName, String argsJson) {
        Session session = Session.reconstruct(sessionId, "/tmp/ws", SessionStatus.ACTIVE,
            CLOCK.instant(), CLOCK.instant());
        runRepository.saveSession(session);
        AgentRun run = AgentRun.start(runId, sessionId, 5, CLOCK.instant())
            .fail("Approval required", CLOCK.instant());
        runRepository.saveRunStarted(run, "plan", "test");

        ApprovalRequest pending = ApprovalRequest.pending(
            approvalId, runId, sessionId, toolCallId, toolName, argsJson, CLOCK.instant()
        );
        approvalRepository.save(pending);
        approvalRepository.update(pending.approve("operator confirmed", CLOCK.instant()));

        toolExecutionRepository.append(runId, new ToolExecutionRecord(
            "te-original", runId, sessionId, toolCallId, toolName, argsJson,
            "Approval required: " + approvalId, true, CLOCK.instant(), CLOCK.instant()
        ));
    }

    private void seedApproval(String approvalId, String runId, String sessionId,
                              String toolCallId, String toolName, ApprovalStatus status) {
        Session session = Session.reconstruct(sessionId, "/tmp/ws", SessionStatus.ACTIVE,
            CLOCK.instant(), CLOCK.instant());
        runRepository.saveSession(session);
        AgentRun run = AgentRun.start(runId, sessionId, 5, CLOCK.instant())
            .fail("Approval required", CLOCK.instant());
        runRepository.saveRunStarted(run, "plan", "test");

        ApprovalRequest pending = ApprovalRequest.pending(
            approvalId, runId, sessionId, toolCallId, toolName, "args", CLOCK.instant()
        );
        approvalRepository.save(pending);
        if (status == ApprovalStatus.APPROVED) {
            approvalRepository.update(pending.approve("ok", CLOCK.instant()));
        } else if (status == ApprovalStatus.REJECTED) {
            approvalRepository.update(pending.reject("no", CLOCK.instant()));
        }

        toolExecutionRepository.append(runId, new ToolExecutionRecord(
            "te-original", runId, sessionId, toolCallId, toolName, "args",
            "Approval required: " + approvalId, true, CLOCK.instant(), CLOCK.instant()
        ));
    }

    private static class AgentToolStub implements AgentTool {
        private final String name;
        private final ToolResult result;

        AgentToolStub(String name, ToolResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name, "Test", "{}");
        }

        @Override
        public ToolResult execute(ToolCall call, ToolExecutionContext context) {
            return new ToolResult(call.id(), result.output(), result.error());
        }
    }

    private static class SpyTool implements AgentTool {
        private final String name;
        private final ToolResult result;
        ToolCall lastCall = null;

        SpyTool(String name, ToolResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition(name, "Test", "{}");
        }

        @Override
        public ToolResult execute(ToolCall call, ToolExecutionContext context) {
            lastCall = call;
            return new ToolResult(call.id(), result.output(), result.error());
        }
    }

    private static class InMemoryApprovalRepository implements ApprovalRepositoryPort {
        private final List<ApprovalRequest> requests = new ArrayList<>();

        @Override
        public void save(ApprovalRequest request) {
            requests.add(request);
        }

        @Override
        public Optional<ApprovalRequest> findById(String id) {
            return requests.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public Optional<ApprovalRequest> findByRunIdAndToolCallId(String runId, String toolCallId) {
            return requests.stream()
                .filter(r -> r.runId().equals(runId) && r.toolCallId().equals(toolCallId))
                .findFirst();
        }

        @Override
        public List<ApprovalRequest> findByRunId(String runId) {
            return requests.stream().filter(r -> r.runId().equals(runId)).toList();
        }

        @Override
        public List<ApprovalRequest> findByStatus(ApprovalStatus status) {
            return requests.stream().filter(r -> r.status() == status).toList();
        }

        @Override
        public List<ApprovalRequest> findAll() {
            return List.copyOf(requests);
        }

        @Override
        public void update(ApprovalRequest request) {
            requests.removeIf(r -> r.id().equals(request.id()));
            requests.add(request);
        }
    }

    private static class InMemoryRunRepository implements RunRepositoryPort {
        private final List<Session> sessions = new ArrayList<>();
        private final List<AgentRunSummary> runs = new ArrayList<>();

        @Override
        public void saveSession(Session session) {
            sessions.removeIf(s -> s.id().equals(session.id()));
            sessions.add(session);
        }

        @Override
        public Optional<Session> findSessionById(String sessionId) {
            return sessions.stream().filter(s -> s.id().equals(sessionId)).findFirst();
        }

        @Override
        public void saveRunStarted(AgentRun run, String mode, String prompt) {
            runs.removeIf(r -> r.id().equals(run.id()));
            runs.add(new AgentRunSummary(
                run.id(), run.sessionId(), mode, run.status(),
                run.currentTurn(), prompt, run.errorReason(),
                run.startedAt(), run.endedAt()
            ));
        }

        @Override
        public void saveRunCompleted(AgentRun run) {
            saveRunCompleted(run.id(), run.currentTurn(), run.endedAt());
        }

        @Override
        public void saveRunCompleted(String runId, int turnCount, Instant completedAt) {
            AgentRunSummary existing = findById(runId).orElseThrow();
            runs.removeIf(r -> r.id().equals(runId));
            runs.add(new AgentRunSummary(
                runId, existing.sessionId(), existing.mode(),
                AgentRunStatus.COMPLETED, turnCount, existing.prompt(),
                existing.errorReason(), existing.startedAt(), completedAt
            ));
        }

        @Override
        public void saveRunFailed(AgentRun run, String reason) {
            saveRunFailed(run.id(), run.currentTurn(), reason, run.endedAt());
        }

        @Override
        public void saveRunFailed(String runId, int turnCount, String reason, Instant completedAt) {
            AgentRunSummary existing = findById(runId).orElseThrow();
            runs.removeIf(r -> r.id().equals(runId));
            runs.add(new AgentRunSummary(
                runId, existing.sessionId(), existing.mode(),
                AgentRunStatus.FAILED, turnCount, existing.prompt(),
                reason, existing.startedAt(), completedAt
            ));
        }

        @Override
        public Optional<AgentRunSummary> findById(String runId) {
            return runs.stream().filter(r -> r.id().equals(runId)).findFirst();
        }
    }

    private static class InMemoryToolExecutionRepository implements ToolExecutionRepositoryPort {
        private final List<ToolExecutionRecord> records = new ArrayList<>();

        @Override
        public void append(String runId, ToolExecutionRecord record) {
            records.add(record);
        }

        @Override
        public List<ToolExecutionRecord> findByRunId(String runId) {
            return records.stream()
                .filter(r -> r.runId().equals(runId))
                .toList();
        }

        void clear() {
            records.clear();
        }
    }
}
