package com.tinyclaw.application.approval;

import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import com.tinyclaw.ports.tool.ToolExecutionDecision;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalGatePolicyTest {

    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext(Path.of("."), "run-1", "sess-1");
    private static final ToolExecutionContext NO_RUN_CONTEXT = new ToolExecutionContext(Path.of("."));

    private final InMemoryApprovalRepository repository = new InMemoryApprovalRepository();
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-10T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void emptyRequiredToolsAllowsExecution() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of(), clock);

        ToolExecutionDecision decision = policy.decide(
            ToolCall.of("t1", "shell_command", "{\"command\":\"echo hi\"}"), CONTEXT
        );

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void requiredToolReturnsRequireApproval() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);

        ToolExecutionDecision decision = policy.decide(
            ToolCall.of("t1", "shell_command", "{\"command\":\"echo hi\"}"), CONTEXT
        );

        assertThat(decision.requiresApproval()).isTrue();
        assertThat(decision.reason()).startsWith("Approval required:");
    }

    @Test
    void missingRunContextReturnsDeny() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);

        ToolExecutionDecision decision = policy.decide(
            ToolCall.of("t1", "shell_command", "{\"command\":\"echo hi\"}"), NO_RUN_CONTEXT
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("run and session context");
    }

    @Test
    void missingSessionContextReturnsDeny() {
        ToolExecutionContext ctx = new ToolExecutionContext(Path.of("."), "run-1", null);
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);

        ToolExecutionDecision decision = policy.decide(
            ToolCall.of("t1", "shell_command", "{\"command\":\"echo hi\"}"), ctx
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("run and session context");
    }

    @Test
    void existingPendingApprovalIsReused() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"echo hi\"}");

        ToolExecutionDecision first = policy.decide(call, CONTEXT);
        String approvalId = extractApprovalId(first.reason());

        ToolExecutionDecision second = policy.decide(call, CONTEXT);

        assertThat(second.requiresApproval()).isTrue();
        assertThat(extractApprovalId(second.reason())).isEqualTo(approvalId);
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void approvalGateCreatesRequestForConfiguredToolWithoutInspectingCommandSafety() {
        // ApprovalGatePolicy independently does not inspect command safety.
        // It only checks whether the tool name is in the required-tools list.
        // Dangerous command priority is guaranteed by ToolRegistry ordering,
        // verified in integration tests such as RunCommandAuditTest.
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"rm -rf /\"}");

        ToolExecutionDecision decision = policy.decide(call, CONTEXT);

        assertThat(decision.requiresApproval()).isTrue();
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void savesArgumentsPreview() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"echo hi\"}");

        policy.decide(call, CONTEXT);

        ApprovalRequest req = repository.findAll().get(0);
        assertThat(req.argumentsPreview()).isEqualTo("{\"command\":\"echo hi\"}");
    }

    @Test
    void approvedApprovalIdAllowsMatchingApproval() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);
        ApprovalRequest pending = ApprovalRequest.pending(
            "apr-1", "run-1", "sess-1", "tc-1", "shell_command", "args", clock.instant()
        );
        repository.save(pending);
        repository.update(pending.approve("ok", clock.instant()));

        ToolExecutionContext ctx = CONTEXT.withApprovedApproval("apr-1");
        ToolExecutionDecision decision = policy.decide(
            ToolCall.of("tc-1", "shell_command", "{\"command\":\"echo hi\"}"), ctx
        );

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void approvedApprovalIdDeniesWhenApprovalNotFound() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);

        ToolExecutionContext ctx = CONTEXT.withApprovedApproval("missing");
        ToolExecutionDecision decision = policy.decide(
            ToolCall.of("tc-1", "shell_command", "{\"command\":\"echo hi\"}"), ctx
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("not found");
    }

    @Test
    void approvedApprovalIdDeniesWhenApprovalStatusIsPending() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);
        ApprovalRequest pending = ApprovalRequest.pending(
            "apr-pending", "run-1", "sess-1", "tc-1", "shell_command", "args", clock.instant()
        );
        repository.save(pending);

        ToolExecutionContext ctx = CONTEXT.withApprovedApproval("apr-pending");
        ToolExecutionDecision decision = policy.decide(
            ToolCall.of("tc-1", "shell_command", "{\"command\":\"echo hi\"}"), ctx
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("not approved");
    }

    @Test
    void approvedApprovalIdDeniesWhenApprovalStatusIsRejected() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);
        ApprovalRequest pending = ApprovalRequest.pending(
            "apr-rejected", "run-1", "sess-1", "tc-1", "shell_command", "args", clock.instant()
        );
        repository.save(pending);
        repository.update(pending.reject("no", clock.instant()));

        ToolExecutionContext ctx = CONTEXT.withApprovedApproval("apr-rejected");
        ToolExecutionDecision decision = policy.decide(
            ToolCall.of("tc-1", "shell_command", "{\"command\":\"echo hi\"}"), ctx
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("not approved");
    }

    @Test
    void approvedApprovalIdDeniesWhenToolCallIdDoesNotMatch() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);
        ApprovalRequest pending = ApprovalRequest.pending(
            "apr-1", "run-1", "sess-1", "tc-1", "shell_command", "args", clock.instant()
        );
        repository.save(pending);
        repository.update(pending.approve("ok", clock.instant()));

        ToolExecutionContext ctx = CONTEXT.withApprovedApproval("apr-1");
        ToolExecutionDecision decision = policy.decide(
            ToolCall.of("tc-other", "shell_command", "{\"command\":\"echo hi\"}"), ctx
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("does not match");
    }

    @Test
    void approvedApprovalIdDeniesWhenToolNameDoesNotMatch() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command", "write_file"), clock);
        ApprovalRequest pending = ApprovalRequest.pending(
            "apr-1", "run-1", "sess-1", "tc-1", "shell_command", "args", clock.instant()
        );
        repository.save(pending);
        repository.update(pending.approve("ok", clock.instant()));

        ToolExecutionContext ctx = CONTEXT.withApprovedApproval("apr-1");
        ToolExecutionDecision decision = policy.decide(
            ToolCall.of("tc-1", "write_file", "{\"path\":\"x\"}"), ctx
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("does not match");
    }

    @Test
    void approvedApprovalIdDeniesWhenRunIdDoesNotMatch() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);
        ApprovalRequest pending = ApprovalRequest.pending(
            "apr-1", "run-1", "sess-1", "tc-1", "shell_command", "args", clock.instant()
        );
        repository.save(pending);
        repository.update(pending.approve("ok", clock.instant()));

        ToolExecutionContext otherRun = new ToolExecutionContext(Path.of("."), "run-other", "sess-1")
            .withApprovedApproval("apr-1");
        ToolExecutionDecision decision = policy.decide(
            ToolCall.of("tc-1", "shell_command", "{\"command\":\"echo hi\"}"), otherRun
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("does not match");
    }

    @Test
    void resumedApprovalIdIsDenied() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);
        ApprovalRequest pending = ApprovalRequest.pending(
            "apr-resumed", "run-1", "sess-1", "tc-1", "shell_command", "args", clock.instant()
        );
        repository.save(pending);
        repository.update(pending.approve("ok", clock.instant()));
        ApprovalRequest approved = repository.findById("apr-resumed").orElseThrow();
        repository.update(approved.markResuming("claiming for resume", clock.instant()));
        ApprovalRequest resuming = repository.findById("apr-resumed").orElseThrow();
        repository.update(resuming.markResumed("already consumed", clock.instant()));

        ToolExecutionContext ctx = CONTEXT.withApprovedApproval("apr-resumed");
        ToolExecutionDecision decision = policy.decide(
            ToolCall.of("tc-1", "shell_command", "{\"command\":\"echo hi\"}"), ctx
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("not approved: RESUMED");
    }

    @Test
    void resumingApprovalIdIsAllowed() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);
        ApprovalRequest pending = ApprovalRequest.pending(
            "apr-resuming", "run-1", "sess-1", "tc-1", "shell_command", "args", clock.instant()
        );
        repository.save(pending);
        repository.update(pending.approve("ok", clock.instant()));
        ApprovalRequest approved = repository.findById("apr-resuming").orElseThrow();
        repository.update(approved.markResuming("claiming for resume", clock.instant()));

        ToolExecutionContext ctx = CONTEXT.withApprovedApproval("apr-resuming");
        ToolExecutionDecision decision = policy.decide(
            ToolCall.of("tc-1", "shell_command", "{\"command\":\"echo hi\"}"), ctx
        );

        assertThat(decision.allowed()).isTrue();
    }

    private String extractApprovalId(String reason) {
        return reason.substring(reason.lastIndexOf(':') + 1).trim();
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

        @Override
        public boolean claimForResume(String approvalId, java.time.Instant now) {
            return false;
        }
    }
}
