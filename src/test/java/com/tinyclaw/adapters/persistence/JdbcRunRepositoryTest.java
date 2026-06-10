package com.tinyclaw.adapters.persistence;

import com.tinyclaw.application.persistence.AgentRunSummary;
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

        AgentRun completed = run.complete(Instant.now());
        repository.saveRunCompleted(completed);

        Optional<AgentRunSummary> found = repository.findById("run-complete");
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(found.get().completedAt()).isNotNull();
    }

    @Test
    void saveRunFailed() {
        Session session = Session.create("sess-fail", "/tmp/ws", Instant.now());
        repository.saveSession(session);
        AgentRun run = AgentRun.start("run-fail", "sess-fail", 5, Instant.now());
        repository.saveRunStarted(run, "agent", "fail me");

        AgentRun failed = run.fail("tool broke", Instant.now());
        repository.saveRunFailed(failed, "tool broke");

        Optional<AgentRunSummary> found = repository.findById("run-fail");
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(found.get().errorReason()).isEqualTo("tool broke");
    }

    @Test
    void findByIdReturnsEmptyForMissingRun() {
        Optional<AgentRunSummary> found = repository.findById("nonexistent-run");
        assertThat(found).isEmpty();
    }
}
