package com.tinyclaw.config;

import com.tinyclaw.application.approval.ApprovalGatePolicy;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import com.tinyclaw.ports.tool.ToolExecutionDecision;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Approval configuration property binding tests.
 */
class ApprovalPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class);

    @Test
    void defaultApprovalEnabledRequiresDefaultTools() {
        contextRunner.run(ctx -> {
            ApprovalProperties props = ctx.getBean(ApprovalProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getRequiredTools()).containsExactly("write_file", "edit_file", "shell_command");
        });
    }

    @Test
    void disabledApprovalBypassesAllTools() {
        contextRunner
            .withPropertyValues("tiny-claw.approval.enabled=false")
            .run(ctx -> {
                ApprovalProperties props = ctx.getBean(ApprovalProperties.class);
                assertThat(props.isEnabled()).isFalse();

                // Verify via real policy that default tools bypass approval
                InMemoryApprovalRepository repo = new InMemoryApprovalRepository();
                ApprovalGatePolicy policy = new ApprovalGatePolicy(
                    repo, props.getRequiredTools(), Clock.systemUTC(), false
                );
                ToolExecutionDecision decision = policy.decide(
                    ToolCall.of("t1", "write_file", "{}"),
                    new ToolExecutionContext(Path.of("."), "run-1", "sess-1")
                );
                assertThat(decision.allowed()).isTrue();
                assertThat(repo.findAll()).isEmpty();
            });
    }

    @Test
    void requiredToolsOverrideTakesEffect() {
        contextRunner
            .withPropertyValues("tiny-claw.approval.required-tools=shell_command,delete_file")
            .run(ctx -> {
                ApprovalProperties props = ctx.getBean(ApprovalProperties.class);
                assertThat(props.getRequiredTools()).containsExactly("shell_command", "delete_file");

                InMemoryApprovalRepository repo = new InMemoryApprovalRepository();
                ApprovalGatePolicy policy = new ApprovalGatePolicy(
                    repo, props.getRequiredTools(), Clock.systemUTC(), true
                );

                // write_file is no longer in the required list -> allowed
                ToolExecutionDecision writeDecision = policy.decide(
                    ToolCall.of("t1", "write_file", "{}"),
                    new ToolExecutionContext(Path.of("."), "run-1", "sess-1")
                );
                assertThat(writeDecision.allowed()).isTrue();

                // shell_command is still required -> approval needed
                ToolExecutionDecision shellDecision = policy.decide(
                    ToolCall.of("t2", "shell_command", "{}"),
                    new ToolExecutionContext(Path.of("."), "run-1", "sess-1")
                );
                assertThat(shellDecision.requiresApproval()).isTrue();
                assertThat(repo.findAll()).hasSize(1);
            });
    }

    @Configuration
    @EnableConfigurationProperties(ApprovalProperties.class)
    static class TestConfig {
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
        public boolean claimForResume(String approvalId, Instant now) {
            return false;
        }
    }
}
