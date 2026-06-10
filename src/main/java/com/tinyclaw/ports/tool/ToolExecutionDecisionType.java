package com.tinyclaw.ports.tool;

/**
 * Possible outcomes of a {@link ToolExecutionPolicy} evaluation.
 */
public enum ToolExecutionDecisionType {
    ALLOW,
    DENY,
    REQUIRE_APPROVAL
}
