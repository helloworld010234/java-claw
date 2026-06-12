package com.tinyclaw.application.benchmark;

import com.tinyclaw.domain.common.DomainGuards;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * A single benchmark case defining setup, prompt, and validation logic.
 *
 * <p>Immutable value object. The setup and validation actions operate on the
 * case-specific workspace directory.</p>
 *
 * @param id          unique case identifier
 * @param name        human-readable name
 * @param prompt      the agent prompt
 * @param setup       writes the initial fixture files into the workspace
 * @param validation  asserts the expected outcome after the agent run
 */
public record BenchmarkCase(
        String id,
        String name,
        String prompt,
        Consumer<Path> setup,
        Consumer<Path> validation) {

    public BenchmarkCase {
        DomainGuards.requireNonBlank(id, "id");
        DomainGuards.requireNonBlank(name, "name");
        DomainGuards.requireNonBlank(prompt, "prompt");
        DomainGuards.requireNonNull(setup, "setup");
        DomainGuards.requireNonNull(validation, "validation");
    }
}
