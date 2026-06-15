package com.tinyclaw.adapters.persistence;

import com.tinyclaw.ports.persistence.AgentRunSummary;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.run.AgentRunStatus;
import com.tinyclaw.domain.session.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class JdbcRunRepositoryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcRunRepository repository;

    @Test
    void saveSessionAndFind() {
        Session session = Session.create("sess-1", "/tmp/ws", Instant.now());
        repository.saveSession(session);

        String workspace = jdbcTemplate.queryForObject(
            "SELECT workspace_path FROM agent_sessions WHERE id = ?", String.class, "sess-1");
        assertThat(workspace).isEqualTo("/tmp/ws");
    }

    @Test
    void saveSessionShouldBeIdempotent() {
        Instant firstTime = Instant.now();
        Session session1 = Session.create("sess-idem", "/tmp/ws1", firstTime);
        repository.saveSession(session1);

        Instant secondTime = firstTime.plusSeconds(10);
        Session session2 = Session.create("sess-idem", "/tmp/ws2", secondTime);
        repository.saveSession(session2);

        String workspace = jdbcTemplate.queryForObject(
            "SELECT workspace_path FROM agent_sessions WHERE id = ?", String.class, "sess-idem");
        assertThat(workspace).isEqualTo("/tmp/ws2");
    }

    @Test
    void canSaveMultipleRunsForSameSession() {
        Session session = Session.create("sess-multi", "/tmp/ws", Instant.now());
        repository.saveSession(session);

        AgentRun run1 = AgentRun.start("run-multi-1", "sess-multi", 5, Instant.now());
        AgentRun run2 = AgentRun.start("run-multi-2", "sess-multi", 5, Instant.now());
        repository.saveRunStarted(run1, "agent", "first");
        repository.saveRunStarted(run2, "agent", "second");

        Optional<AgentRunSummary> found1 = repository.findById("run-multi-1");
        Optional<AgentRunSummary> found2 = repository.findById("run-multi-2");
        assertThat(found1).isPresent();
        assertThat(found2).isPresent();
        assertThat(found1.get().prompt()).isEqualTo("first");
        assertThat(found2.get().prompt()).isEqualTo("second");
    }

    @Test
    void saveRunStartedAndFind() {
        Session session = Session.create("sess-run", "/tmp/ws", Instant.now());
        repository.saveSession(session);

        AgentRun run = AgentRun.start("run-1", "sess-run", 5, Instant.now());
        repository.saveRunStarted(run, "agent", "hello");

        Optional<AgentRunSummary> found = repository.findById("run-1");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo("run-1");
        assertThat(found.get().sessionId()).isEqualTo("sess-run");
        assertThat(found.get().mode()).isEqualTo("agent");
        assertThat(found.get().prompt()).isEqualTo("hello");
        assertThat(found.get().status()).isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void saveRunCompleted() {
        Session session = Session.create("sess-complete", "/tmp/ws", Instant.now());
        repository.saveSession(session);
        AgentRun run = AgentRun.start("run-complete", "sess-complete", 5, Instant.now());
        repository.saveRunStarted(run, "plan", "do it");

        repository.saveRunCompleted(run.id(), 3, Instant.now());

        Optional<AgentRunSummary> found = repository.findById("run-complete");
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(found.get().turnCount()).isEqualTo(3);
        assertThat(found.get().completedAt()).isNotNull();
    }

    @Test
    void saveRunFailed() {
        Session session = Session.create("sess-fail", "/tmp/ws", Instant.now());
        repository.saveSession(session);
        AgentRun run = AgentRun.start("run-fail", "sess-fail", 5, Instant.now());
        repository.saveRunStarted(run, "agent", "fail me");

        repository.saveRunFailed(run.id(), 2, "tool broke", Instant.now());

        Optional<AgentRunSummary> found = repository.findById("run-fail");
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(found.get().turnCount()).isEqualTo(2);
        assertThat(found.get().errorReason()).isEqualTo("tool broke");
    }

    @Test
    void findByIdReturnsEmptyForMissingRun() {
        Optional<AgentRunSummary> found = repository.findById("nonexistent-run");
        assertThat(found).isEmpty();
    }

    @Test
    void saveRunWaitingForApprovalPersistsApprovalId() {
        Session session = Session.create("sess-wait", "/tmp/ws", Instant.now());
        repository.saveSession(session);
        AgentRun run = AgentRun.start("run-wait", "sess-wait", 5, Instant.now());
        repository.saveRunStarted(run, "agent", "wait for approval");

        repository.saveRunWaitingForApproval("run-wait", 1, "app-123", Instant.now());

        Optional<AgentRunSummary> found = repository.findById("run-wait");
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(found.get().approvalId()).isEqualTo("app-123");
    }
}
