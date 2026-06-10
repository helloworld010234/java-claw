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
        Session session = Session.create(sessionId, "/tmp", Instant.now());
        runRepo.saveSession(session);
        AgentRun run = AgentRun.start(runId, sessionId, 5, Instant.now());
        runRepo.saveRunStarted(run, "plan", "test");
    }

    @Test
    void saveAndFindById() {
        seedSessionAndRun("sess-s1", "run-s1");
        ApprovalRequest request = ApprovalRequest.pending(
            "apr-1", "run-s1", "sess-s1", "tc-1", "shell_command",
            "{\"command\":\"echo hi\"}", Instant.now()
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
            "apr-2", "run-f1", "sess-f1", "tc-2", "shell_command", "args1", Instant.now()
        ));
        repository.save(ApprovalRequest.pending(
            "apr-3", "run-f1", "sess-f1", "tc-3", "write_file", "args2", Instant.now()
        ));

        List<ApprovalRequest> found = repository.findByRunId("run-f1");
        assertThat(found).hasSize(2);
    }

    @Test
    void findByStatus() {
        seedSessionAndRun("sess-f2", "run-f2");
        repository.save(ApprovalRequest.pending(
            "apr-4", "run-f2", "sess-f2", "tc-4", "shell_command", "args", Instant.now()
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
            "apr-5", "run-u1", "sess-u1", "tc-5", "shell_command", "args", Instant.now()
        );
        repository.save(request);

        ApprovalRequest approved = request.approve("operator confirmed", Instant.now());
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
            "apr-6", "run-u2", "sess-u2", "tc-6", "shell_command", "args", Instant.now()
        );
        repository.save(request);

        ApprovalRequest rejected = request.reject("unsafe", Instant.now());
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
            "apr-7", "run-f3", "sess-f3", "tc-7", "shell_command", "args", Instant.now()
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
}
