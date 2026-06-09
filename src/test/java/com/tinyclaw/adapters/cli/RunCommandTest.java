package com.tinyclaw.adapters.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Picocli run 命令单元测试。
 */
class RunCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void runWithValidArgumentsShouldReturnZero() {
        RunCommand command = new RunCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute(
            "--prompt", "Hello agent",
            "--dir", tempDir.toString(),
            "--session", "test-session-001"
        );

        assertThat(exitCode).isZero();
    }

    @Test
    void runWithoutPromptShouldFail() {
        RunCommand command = new RunCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute(
            "--dir", tempDir.toString()
        );

        assertThat(exitCode).isNotZero();
    }

    @Test
    void runWithNonExistentDirShouldFail() {
        RunCommand command = new RunCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute(
            "--prompt", "Hello",
            "--dir", "/nonexistent/path/12345"
        );

        assertThat(exitCode).isNotZero();
    }
}
