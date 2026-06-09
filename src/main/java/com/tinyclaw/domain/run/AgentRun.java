package com.tinyclaw.domain.run;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.common.TinyClawDomainException;

import java.time.Instant;

/**
 * Agent 单次运行（Run）。
 *
 * <p>不可变状态机。状态转换通过返回新实例实现。</p>
 */
public final class AgentRun {

    private final String id;
    private final String sessionId;
    private final AgentRunStatus status;
    private final int maxTurns;
    private final int currentTurn;
    private final Instant startedAt;
    private final Instant endedAt;
    private final String errorReason;

    private AgentRun(String id, String sessionId, AgentRunStatus status, int maxTurns, int currentTurn,
                     Instant startedAt, Instant endedAt, String errorReason) {
        this.id = DomainGuards.requireNonBlank(id, "id");
        this.sessionId = DomainGuards.requireNonBlank(sessionId, "sessionId");
        this.status = DomainGuards.requireNonNull(status, "status");
        this.maxTurns = DomainGuards.requirePositive(maxTurns, "maxTurns");
        this.currentTurn = DomainGuards.requireNonNegative(currentTurn, "currentTurn");
        if (currentTurn > maxTurns) {
            throw new TinyClawDomainException(
                    "currentTurn (" + currentTurn + ") must not exceed maxTurns (" + maxTurns + ")");
        }
        this.startedAt = DomainGuards.requireNonNull(startedAt, "startedAt");
        this.endedAt = endedAt;
        this.errorReason = errorReason;
    }

    public String id() {
        return id;
    }

    public String sessionId() {
        return sessionId;
    }

    public AgentRunStatus status() {
        return status;
    }

    public int maxTurns() {
        return maxTurns;
    }

    public int currentTurn() {
        return currentTurn;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }

    public String errorReason() {
        return errorReason;
    }

    /**
     * 启动新的 Run。
     */
    public static AgentRun start(String id, String sessionId, int maxTurns, Instant now) {
        DomainGuards.requireNonNull(now, "now");
        return new AgentRun(id, sessionId, AgentRunStatus.RUNNING, maxTurns, 0, now, null, null);
    }

    /**
     * 进入下一轮，currentTurn + 1。
     *
     * @throws TinyClawDomainException 如果超过 maxTurns 或已是终止状态
     */
    public AgentRun nextTurn() {
        assertNotTerminal("nextTurn");
        if (currentTurn >= maxTurns) {
            throw new TinyClawDomainException(
                    "Cannot advance turn: currentTurn (" + currentTurn + ") already at maxTurns (" + maxTurns + ")");
        }
        return new AgentRun(id, sessionId, status, maxTurns, currentTurn + 1, startedAt, endedAt, errorReason);
    }

    /**
     * 标记为完成。
     */
    public AgentRun complete(Instant now) {
        DomainGuards.requireNonNull(now, "now");
        assertNotTerminal("complete");
        return new AgentRun(id, sessionId, AgentRunStatus.COMPLETED, maxTurns, currentTurn, startedAt, now, errorReason);
    }

    /**
     * 标记为失败。
     *
     * @param reason 失败原因，必须非空
     */
    public AgentRun fail(String reason, Instant now) {
        DomainGuards.requireNonBlank(reason, "reason");
        DomainGuards.requireNonNull(now, "now");
        assertNotTerminal("fail");
        return new AgentRun(id, sessionId, AgentRunStatus.FAILED, maxTurns, currentTurn, startedAt, now, reason);
    }

    /**
     * 标记为等待审批。
     */
    public AgentRun waitForApproval() {
        assertNotTerminal("waitForApproval");
        return new AgentRun(id, sessionId, AgentRunStatus.WAITING_APPROVAL, maxTurns, currentTurn, startedAt, endedAt, errorReason);
    }

    private boolean isTerminal() {
        return status == AgentRunStatus.COMPLETED
                || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELLED;
    }

    private void assertNotTerminal(String operation) {
        if (isTerminal()) {
            throw new TinyClawDomainException(
                    "Cannot perform '" + operation + "' on run in terminal status: " + status);
        }
    }
}
