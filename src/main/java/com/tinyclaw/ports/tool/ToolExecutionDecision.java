package com.tinyclaw.ports.tool;

/**
 * Result of a {@link ToolExecutionPolicy} evaluation.
 *
 * @param type   the decision type
 * @param reason human-readable explanation; must not contain secrets or env vars
 */
public record ToolExecutionDecision(ToolExecutionDecisionType type, String reason) {

    /**
     * Creates an approval decision with no reason needed.
     */
    public static ToolExecutionDecision allow() {
        return new ToolExecutionDecision(ToolExecutionDecisionType.ALLOW, "");
    }

    /**
     * Creates a rejection decision with the given reason.
     */
    public static ToolExecutionDecision deny(String reason) {
        return new ToolExecutionDecision(ToolExecutionDecisionType.DENY, reason);
    }

    /**
     * Creates a decision that requires human approval before proceeding.
     */
    public static ToolExecutionDecision requireApproval(String reason) {
        return new ToolExecutionDecision(ToolExecutionDecisionType.REQUIRE_APPROVAL, reason);
    }

    /**
     * Returns true if the tool call may proceed.
     */
    public boolean allowed() {
        return type == ToolExecutionDecisionType.ALLOW;
    }

    /**
     * Returns true if human approval is required.
     */
    public boolean requiresApproval() {
        return type == ToolExecutionDecisionType.REQUIRE_APPROVAL;
    }
}
