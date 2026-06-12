package com.tinyclaw.ports.benchmark;

import java.nio.file.Path;

/**
 * Port for running an external validation command (e.g. {@code go test}) against
 * a benchmark workspace.
 *
 * <p>The application layer depends on this SPI; concrete process execution is
 * provided by an adapter so tests can substitute a fake runner.</p>
 */
@FunctionalInterface
public interface ValidationCommandRunnerPort {

    /**
     * Runs the validation command in the given workspace.
     *
     * @param workspace the workspace directory
     * @return the validation result
     */
    GoTestResult runGoTest(Path workspace);
}
