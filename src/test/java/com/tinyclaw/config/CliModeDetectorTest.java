package com.tinyclaw.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CliModeDetectorTest {

    @Test
    void runIsCliMode() {
        assertThat(CliModeDetector.isCliMode(new String[]{"run", "--prompt", "Hello"})).isTrue();
    }

    @Test
    void toolIsCliMode() {
        assertThat(CliModeDetector.isCliMode(new String[]{"tool", "--name", "read_file"})).isTrue();
    }

    @Test
    void runsIsCliMode() {
        assertThat(CliModeDetector.isCliMode(new String[]{"runs", "show", "--run-id", "abc"})).isTrue();
    }

    @Test
    void approvalsIsCliMode() {
        assertThat(CliModeDetector.isCliMode(new String[]{"approvals", "list"})).isTrue();
    }

    @Test
    void onlySpringArgsIsNotCliMode() {
        assertThat(CliModeDetector.isCliMode(new String[]{
            "--spring.profiles.active=test",
            "--server.port=0"
        })).isFalse();
    }

    @Test
    void unknownCommandIsNotCliMode() {
        assertThat(CliModeDetector.isCliMode(new String[]{"unknown", "--arg", "value"})).isFalse();
    }

    @Test
    void nullArgsReturnsFalse() {
        assertThat(CliModeDetector.isCliMode(null)).isFalse();
    }
}
