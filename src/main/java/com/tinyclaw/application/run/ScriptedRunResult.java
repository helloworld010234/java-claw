package com.tinyclaw.application.run;

import java.util.List;

/**
 * Result of executing a scripted run plan.
 */
public record ScriptedRunResult(boolean success, List<ScriptedRunStepResult> steps) {
}
