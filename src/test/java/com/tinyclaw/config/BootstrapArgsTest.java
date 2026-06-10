package com.tinyclaw.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapArgsTest {

    @Test
    void tinyclawSpaceSeparatedBecomesEqualsForm() {
        String[] result = BootstrapArgs.normalize(
            new String[]{"--tinyclaw.approval.required-tools", "shell_command"}
        );
        assertThat(result).containsExactly("--tinyclaw.approval.required-tools=shell_command");
    }

    @Test
    void tinyclawEqualsFormUnchanged() {
        String[] result = BootstrapArgs.normalize(
            new String[]{"--tinyclaw.approval.required-tools=shell_command"}
        );
        assertThat(result).containsExactly("--tinyclaw.approval.required-tools=shell_command");
    }

    @Test
    void springSpaceSeparatedBecomesEqualsForm() {
        String[] result = BootstrapArgs.normalize(
            new String[]{"--spring.profiles.active", "test"}
        );
        assertThat(result).containsExactly("--spring.profiles.active=test");
    }

    @Test
    void serverSpaceSeparatedBecomesEqualsForm() {
        String[] result = BootstrapArgs.normalize(
            new String[]{"--server.port", "0"}
        );
        assertThat(result).containsExactly("--server.port=0");
    }

    @Test
    void managementSpaceSeparatedBecomesEqualsForm() {
        String[] result = BootstrapArgs.normalize(
            new String[]{"--management.endpoints.web.exposure.include", "*"}
        );
        assertThat(result).containsExactly("--management.endpoints.web.exposure.include=*");
    }

    @Test
    void loggingSpaceSeparatedBecomesEqualsForm() {
        String[] result = BootstrapArgs.normalize(
            new String[]{"--logging.level.org.springframework", "debug"}
        );
        assertThat(result).containsExactly("--logging.level.org.springframework=debug");
    }

    @Test
    void cliArgsAreNotConverted() {
        String[] result = BootstrapArgs.normalize(
            new String[]{"run", "--prompt", "Hello", "--dir", "D:\\work", "--session", "smoke"}
        );
        assertThat(result).containsExactly(
            "run", "--prompt", "Hello", "--dir", "D:\\work", "--session", "smoke"
        );
    }

    @Test
    void configArgWithoutValueIsPreserved() {
        String[] result = BootstrapArgs.normalize(
            new String[]{"--spring.profiles.active"}
        );
        assertThat(result).containsExactly("--spring.profiles.active");
    }

    @Test
    void configArgFollowedByAnotherOptionIsPreserved() {
        String[] result = BootstrapArgs.normalize(
            new String[]{"--spring.profiles.active", "--server.port", "0"}
        );
        assertThat(result).containsExactly(
            "--spring.profiles.active", "--server.port=0"
        );
    }

    @Test
    void nullArgsReturnsEmptyArray() {
        String[] result = BootstrapArgs.normalize(null);
        assertThat(result).isEmpty();
    }

    @Test
    void mixedConfigAndCliArgs() {
        String[] result = BootstrapArgs.normalize(
            new String[]{
                "run",
                "--tinyclaw.approval.required-tools", "shell_command",
                "--prompt", "Hello",
                "--spring.profiles.active", "test",
                "--dir", "D:\\work",
                "--server.port", "0"
            }
        );
        assertThat(result).containsExactly(
            "run",
            "--tinyclaw.approval.required-tools=shell_command",
            "--prompt", "Hello",
            "--spring.profiles.active=test",
            "--dir", "D:\\work",
            "--server.port=0"
        );
    }

    @Test
    void emptyArrayReturnsEmptyArray() {
        String[] result = BootstrapArgs.normalize(new String[0]);
        assertThat(result).isEmpty();
    }
}
