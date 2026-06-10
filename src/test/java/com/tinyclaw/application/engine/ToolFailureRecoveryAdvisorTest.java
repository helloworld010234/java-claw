package com.tinyclaw.application.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolFailureRecoveryAdvisorTest {

    private final ToolFailureRecoveryAdvisor advisor = new ToolFailureRecoveryAdvisor();

    @Test
    void readFileMissingPathAddsHint() {
        String raw = "File does not exist or cannot be accessed: missing.txt";
        String result = advisor.advise("read_file", raw);

        assertThat(result).startsWith(raw);
        assertThat(result).contains("[Recovery hint]:");
        assertThat(result).contains("shell_command");
    }

    @Test
    void writeFileNoSuchFileAddsHint() {
        String raw = "Error: no such file or directory";
        String result = advisor.advise("write_file", raw);

        assertThat(result).contains("[Recovery hint]:");
    }

    @Test
    void editFileOldTextNotFoundAddsHint() {
        String raw = "oldText not found in file: src.txt";
        String result = advisor.advise("edit_file", raw);

        assertThat(result).startsWith(raw);
        assertThat(result).contains("read_file");
    }

    @Test
    void editFileMultipleMatchesAddsHint() {
        String raw = "oldText appears multiple times in file: src.txt";
        String result = advisor.advise("edit_file", raw);

        assertThat(result).contains("more surrounding lines");
    }

    @Test
    void shellCommandNotFoundAddsHint() {
        String raw = "Command not found: xyz";
        String result = advisor.advise("shell_command", raw);

        assertThat(result).contains("Command not found. Verify");
    }

    @Test
    void shellCommandTimeoutAddsHint() {
        String raw = "some output\n[Command timed out after 30 seconds]";
        String result = advisor.advise("shell_command", raw);

        assertThat(result).contains("Command timed out. For long-running services");
    }

    @Test
    void unknownErrorReturnsRawOutput() {
        String raw = "Something weird happened";
        String result = advisor.advise("read_file", raw);

        assertThat(result).isEqualTo(raw);
    }

    @Test
    void nullToolNameReturnsRawOutput() {
        assertThat(advisor.advise(null, "error")).isEqualTo("error");
    }

    @Test
    void nullRawOutputReturnsEmptyString() {
        assertThat(advisor.advise("read_file", null)).isEmpty();
    }
}
