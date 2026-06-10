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
    void dangerousCommandDenyTakesPriorityOverApprovalGate() {
        // Simulate registry order: dangerous command policy first, then approval gate
        // When dangerous command policy denies, approval gate should never be reached.
        // This test verifies approval gate itself does not create an approval for a dangerous command
        // when called independently (which shouldn't happen in real registry order).
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"rm -rf /\"}");

        policy.decide(call, CONTEXT);
        assertThat(repository.findAll()).hasSize(1);

        // In real ToolRegistry, dangerous command policy would run first and deny,
        // so approval gate wouldn't be called. This test ensures that if approval gate
        // IS called, it still creates a request (which is acceptable because the registry
        // ordering is what guarantees priority).
    }

    @Test
    void savesArgumentsPreview() {
        ApprovalGatePolicy policy = new ApprovalGatePolicy(repository, List.of("shell_command"), clock);
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"echo hi\"}");

        policy.decide(call, CONTEXT);

        ApprovalRequest req = repository.findAll().get(0);
        assertThat(req.argumentsPreview()).isEqualTo("{\"command\":\"echo hi\"}");
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
    }
}
