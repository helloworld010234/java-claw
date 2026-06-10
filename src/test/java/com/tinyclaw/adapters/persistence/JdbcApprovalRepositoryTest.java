package com.tinyclaw.adapters.persistence;

import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import org.junit.jupiter.api.BeforeEach;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class JdbcApprovalRepositoryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcApprovalRepository repository;

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM approval_requests");
        jdbcTemplate.update("DELETE FROM tool_executions");
        jdbcTemplate.update("DELETE FROM agent_messages");
        jdbcTemplate.update("DELETE FROM agent_runs");
        jdbcTemplate.update("DELETE FROM agent_sessions");
    }

    private void seedSessionAndRun(String sessionId, String runId) {
        JdbcRunRepository runRepo = new JdbcRunRepository(jdbcTemplate);
        Session session = Session.create(sessionId, "/tmp", BASE);
        runRepo.saveSession(session);
        AgentRun run = AgentRun.start(runId, sessionId, 5, BASE);
        runRepo.saveRunStarted(run, "plan", "test");
    }

    @Test
    void saveAndFindById() {
        seedSessionAndRun("sess-s1", "run-s1");
        ApprovalRequest request = ApprovalRequest.pending(
            "apr-1", "run-s1", "sess-s1", "tc-1", "shell_command",
            "{\"command\":\"echo hi\"}", BASE
        );

        repository.save(request);

        Optional<ApprovalRequest> found = repository.findById("apr-1");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo("apr-1");
        assertThat(found.get().toolName()).isEqualTo("shell_command");
        assertThat(found.get().status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void findByRunId() {
        seedSessionAndRun("sess-f1", "run-f1");
        repository.save(ApprovalRequest.pending(
            "apr-2", "run-f1", "sess-f1", "tc-2", "shell_command", "args1", BASE
        ));
        repository.save(ApprovalRequest.pending(
            "apr-3", "run-f1", "sess-f1", "tc-3", "write_file", "args2", BASE
        ));

        List<ApprovalRequest> found = repository.findByRunId("run-f1");
        assertThat(found).hasSize(2);
    }

    @Test
    void findByStatus() {
        seedSessionAndRun("sess-f2", "run-f2");
        repository.save(ApprovalRequest.pending(
            "apr-4", "run-f2", "sess-f2", "tc-4", "shell_command", "args", BASE
        ));

        List<ApprovalRequest> pending = repository.findByStatus(ApprovalStatus.PENDING);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).id()).isEqualTo("apr-4");

        List<ApprovalRequest> approved = repository.findByStatus(ApprovalStatus.APPROVED);
        assertThat(approved).isEmpty();
    }

    @Test
    void updateApprove() {
        seedSessionAndRun("sess-u1", "run-u1");
        ApprovalRequest request = ApprovalRequest.pending(
            "apr-5", "run-u1", "sess-u1", "tc-5", "shell_command", "args", BASE
        );
        repository.save(request);

        ApprovalRequest approved = request.approve("operator confirmed", BASE.plusMillis(1));
        repository.update(approved);

        Optional<ApprovalRequest> found = repository.findById("apr-5");
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(found.get().decisionReason()).isEqualTo("operator confirmed");
        assertThat(found.get().decidedAt()).isNotNull();
    }

    @Test
    void updateReject() {
        seedSessionAndRun("sess-u2", "run-u2");
        ApprovalRequest request = ApprovalRequest.pending(
            "apr-6", "run-u2", "sess-u2", "tc-6", "shell_command", "args", BASE
        );
        repository.save(request);

        ApprovalRequest rejected = request.reject("unsafe", BASE.plusMillis(1));
        repository.update(rejected);

        Optional<ApprovalRequest> found = repository.findById("apr-6");
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(found.get().decisionReason()).isEqualTo("unsafe");
    }

    @Test
    void findByRunIdAndToolCallId() {
        seedSessionAndRun("sess-f3", "run-f3");
        repository.save(ApprovalRequest.pending(
            "apr-7", "run-f3", "sess-f3", "tc-7", "shell_command", "args", BASE
        ));

        Optional<ApprovalRequest> found = repository.findByRunIdAndToolCallId("run-f3", "tc-7");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo("apr-7");
    }

    @Test
    void findByIdReturnsEmptyForMissing() {
        Optional<ApprovalRequest> found = repository.findById("missing");
        assertThat(found).isEmpty();
    }

    @Test
    void findByRunIdReturnsEmptyForMissingRun() {
        List<ApprovalRequest> found = repository.findByRunId("missing-run");
        assertThat(found).isEmpty();
    }

    @Test
    void findByRunIdAndToolCallIdReturnsEmptyForMissing() {
        Optional<ApprovalRequest> found = repository.findByRunIdAndToolCallId("missing", "missing");
        assertThat(found).isEmpty();
    }

    @Test
    void claimForResumeOnApprovedReturnsTrueAndSetsResuming() {
        seedSessionAndRun("sess-claim", "run-claim");
        ApprovalRequest request = ApprovalRequest.pending(
            "apr-claim", "run-claim", "sess-claim", "tc-1", "shell_command", "args", BASE
        );
        repository.save(request);
        repository.update(request.approve("ok", BASE.plusMillis(1)));

        boolean claimed = repository.claimForResume("apr-claim", BASE.plusMillis(1));

        assertThat(claimed).isTrue();
        ApprovalRequest after = repository.findById("apr-claim").orElseThrow();
        assertThat(after.status()).isEqualTo(ApprovalStatus.RESUMING);
        assertThat(after.decisionReason()).isEqualTo("claiming for resume");
    }

    @Test
    void claimForResumeOnAlreadyResumingReturnsFalse() {
        seedSessionAndRun("sess-claim2", "run-claim2");
        ApprovalRequest request = ApprovalRequest.pending(
            "apr-claim2", "run-claim2", "sess-claim2", "tc-1", "shell_command", "args", BASE
        );
        repository.save(request);
        repository.update(request.approve("ok", BASE.plusMillis(1)));
        repository.claimForResume("apr-claim2", BASE.plusMillis(1));

        boolean second = repository.claimForResume("apr-claim2", BASE.plusMillis(2));

        assertThat(second).isFalse();
    }

    @Test
    void claimForResumeOnResumedReturnsFalse() {
        seedSessionAndRun("sess-claim3", "run-claim3");
        ApprovalRequest request = ApprovalRequest.pending(
            "apr-claim3", "run-claim3", "sess-claim3", "tc-1", "shell_command", "args", BASE
        );
        repository.save(request);
        repository.update(request.approve("ok", BASE.plusMillis(1)));
        repository.claimForResume("apr-claim3", BASE.plusMillis(1));
        ApprovalRequest resuming = repository.findById("apr-claim3").orElseThrow();
        repository.update(resuming.markResumed("done", BASE.plusMillis(2)));

        boolean claimed = repository.claimForResume("apr-claim3", BASE.plusMillis(2));

        assertThat(claimed).isFalse();
    }

    @Test
    void claimForResumeOnPendingReturnsFalse() {
        seedSessionAndRun("sess-claim4", "run-claim4");
        ApprovalRequest request = ApprovalRequest.pending(
            "apr-claim4", "run-claim4", "sess-claim4", "tc-1", "shell_command", "args", BASE
        );
        repository.save(request);

        boolean claimed = repository.claimForResume("apr-claim4", BASE.plusMillis(1));

        assertThat(claimed).isFalse();
    }

    @Test
    void claimForResumeOnRejectedReturnsFalse() {
        seedSessionAndRun("sess-claim5", "run-claim5");
        ApprovalRequest request = ApprovalRequest.pending(
            "apr-claim5", "run-claim5", "sess-claim5", "tc-1", "shell_command", "args", BASE
        );
        repository.save(request);
        repository.update(request.reject("no", BASE.plusMillis(1)));

        boolean claimed = repository.claimForResume("apr-claim5", BASE.plusMillis(1));

        assertThat(claimed).isFalse();
    }

    @Test
    void claimForResumeOnExpiredReturnsFalse() {
        seedSessionAndRun("sess-claim6", "run-claim6");
        ApprovalRequest request = ApprovalRequest.pending(
            "apr-claim6", "run-claim6", "sess-claim6", "tc-1", "shell_command", "args", BASE
        );
        repository.save(request);
        repository.update(request.expire(BASE.plusMillis(1)));

        boolean claimed = repository.claimForResume("apr-claim6", BASE.plusMillis(1));

        assertThat(claimed).isFalse();
    }

    @Test
    void concurrentClaimForResumeOnlyOneSucceeds() throws Exception {
        seedSessionAndRun("sess-conc", "run-conc");
        ApprovalRequest request = ApprovalRequest.pending(
            "apr-conc", "run-conc", "sess-conc", "tc-1", "shell_command", "args", BASE
        );
        repository.save(request);
        repository.update(request.approve("ok", BASE.plusMillis(1)));

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Boolean> f1 = executor.submit(() -> {
                latch.await();
                return repository.claimForResume("apr-conc", BASE.plusMillis(1));
            });
            java.util.concurrent.Future<Boolean> f2 = executor.submit(() -> {
                latch.await();
                return repository.claimForResume("apr-conc", BASE.plusMillis(1));
            });
            Thread.sleep(50);
            latch.countDown();

            boolean r1 = f1.get();
            boolean r2 = f2.get();
            long successCount = (r1 ? 1 : 0) + (r2 ? 1 : 0);
            assertThat(successCount).isEqualTo(1);

            ApprovalRequest after = repository.findById("apr-conc").orElseThrow();
            assertThat(after.status()).isEqualTo(ApprovalStatus.RESUMING);
        } finally {
            executor.shutdownNow();
        }
    }
}
