package com.tinyclaw.application.run;

/**
 * Result of executing a single scripted run step.
 */
public record ScriptedRunStepResult(String id, String tool, boolean error, String output) {
}
