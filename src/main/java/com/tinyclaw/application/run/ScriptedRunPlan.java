package com.tinyclaw.application.run;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.common.TinyClawDomainException;

import java.util.List;

/**
 * A scripted run plan: ordered tool calls with error handling policy.
 */
public record ScriptedRunPlan(Boolean stopOnError, List<ScriptedRunStep> steps) {

    public ScriptedRunPlan {
        DomainGuards.requireNonNull(steps, "steps");
        if (steps.isEmpty()) {
            throw new TinyClawDomainException("steps must not be empty");
        }
    }

    /**
     * Returns true if the plan should stop on the first error.
     * Defaults to true when not explicitly set.
     */
    public boolean shouldStopOnError() {
        return stopOnError == null || stopOnError;
    }
}
