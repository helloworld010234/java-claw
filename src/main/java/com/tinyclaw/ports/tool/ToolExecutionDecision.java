package com.tinyclaw.ports.tool;

/**
 * Result of a {@link ToolExecutionPolicy} evaluation.
 *
 * @param allowed true if the tool call may proceed
 * @param reason  human-readable explanation; must not contain secrets or env vars
 */
public record ToolExecutionDecision(boolean allowed, String reason) {

    /**
     * Creates an approval decision with no reason needed.
     */
    public static ToolExecutionDecision allow() {
        return new ToolExecutionDecision(true, "");
    }

    /**
     * Creates a rejection decision with the given reason.
     */
    public static ToolExecutionDecision deny(String reason) {
        return new ToolExecutionDecision(false, reason);
    }
}
