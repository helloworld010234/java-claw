package com.tinyclaw.domain.run;

import com.tinyclaw.domain.common.TinyClawDomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunTest {

    @Test
    void startDefaultsToRunning() {
        Instant now = Instant.now();
        AgentRun run = AgentRun.start("run-1", "sess-1", 10, now);
        assertThat(run.id()).isEqualTo("run-1");
        assertThat(run.sessionId()).isEqualTo("sess-1");
        assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(run.maxTurns()).isEqualTo(10);
        assertThat(run.currentTurn()).isZero();
        assertThat(run.startedAt()).isEqualTo(now);
        assertThat(run.endedAt()).isNull();
        assertThat(run.errorReason()).isNull();
    }

    @Test
    void nextTurnIncrementsCurrentTurn() {
        Instant now = Instant.now();
        AgentRun run = AgentRun.start("run-1", "sess-1", 10, now);
        AgentRun next = run.nextTurn();
        assertThat(next.currentTurn()).isEqualTo(1);
        assertThat(next.status()).isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void nextTurnExceedsMaxTurnsThrows() {
        Instant now = Instant.now();
        AgentRun run = AgentRun.start("run-1", "sess-1", 2, now);
        run = run.nextTurn();
        run = run.nextTurn();
        assertThat(run.currentTurn()).isEqualTo(2);
        assertThatThrownBy(run::nextTurn)
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("Cannot advance turn");
    }

    @Test
    void completeSetsCompletedAndEndedAt() {
        Instant now = Instant.now();
        AgentRun run = AgentRun.start("run-1", "sess-1", 10, now);
        Instant ended = now.plusSeconds(5);
        AgentRun completed = run.complete(ended);
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.endedAt()).isEqualTo(ended);
    }

    @Test
    void failSetsFailedAndErrorReason() {
        Instant now = Instant.now();
        AgentRun run = AgentRun.start("run-1", "sess-1", 10, now);
        Instant ended = now.plusSeconds(5);
        AgentRun failed = run.fail("LLM timeout", ended);
        assertThat(failed.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(failed.endedAt()).isEqualTo(ended);
        assertThat(failed.errorReason()).isEqualTo("LLM timeout");
    }

    @Test
    void waitForApprovalSetsStatus() {
        Instant now = Instant.now();
        AgentRun run = AgentRun.start("run-1", "sess-1", 10, now);
        AgentRun waiting = run.waitForApproval();
        assertThat(waiting.status()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
    }

    @Test
    void nextTurnOnCompletedThrows() {
        Instant now = Instant.now();
        AgentRun run = AgentRun.start("run-1", "sess-1", 10, now).complete(now.plusSeconds(1));
        assertThatThrownBy(run::nextTurn)
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("terminal status");
    }

    @Test
    void nextTurnOnFailedThrows() {
        Instant now = Instant.now();
        AgentRun run = AgentRun.start("run-1", "sess-1", 10, now).fail("error", now.plusSeconds(1));
        assertThatThrownBy(run::nextTurn)
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("terminal status");
    }

    @Test
    void completeOnFailedThrows() {
        Instant now = Instant.now();
        AgentRun run = AgentRun.start("run-1", "sess-1", 10, now).fail("error", now.plusSeconds(1));
        assertThatThrownBy(() -> run.complete(now.plusSeconds(2)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("terminal status");
    }

    @Test
    void failWithBlankReasonThrows() {
        Instant now = Instant.now();
        AgentRun run = AgentRun.start("run-1", "sess-1", 10, now);
        assertThatThrownBy(() -> run.fail("  ", now.plusSeconds(1)))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void zeroMaxTurnsThrows() {
        assertThatThrownBy(() -> AgentRun.start("run-1", "sess-1", 0, Instant.now()))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("maxTurns");
    }

    @Test
    void negativeMaxTurnsThrows() {
        assertThatThrownBy(() -> AgentRun.start("run-1", "sess-1", -1, Instant.now()))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("maxTurns");
    }
}
