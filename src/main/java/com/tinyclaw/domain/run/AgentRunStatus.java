package com.tinyclaw.domain.run;

/**
 * Agent Run 状态枚举。
 */
public enum AgentRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    WAITING_APPROVAL;

    /**
     * Returns true if this status is a terminal state.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /**
     * Returns true if this status represents a failure.
     */
    public boolean isFailure() {
        return this == FAILED || this == CANCELLED;
    }
}
