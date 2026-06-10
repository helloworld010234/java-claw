package com.tinyclaw.application.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.ports.tool.ToolExecutionDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DangerousCommandPolicyTest {

    private final DangerousCommandPolicy policy = new DangerousCommandPolicy();

    @Test
    void readFileIsAlwaysAllowed() {
        ToolCall call = ToolCall.of("t1", "read_file", "{\"path\":\"secret.txt\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void writeFileIsAllowedByThisPolicy() {
        ToolCall call = ToolCall.of("t1", "write_file", "{\"path\":\"x.txt\",\"content\":\"x\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void shellWithRmRfIsDenied() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"rm -rf /\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("recursive delete");
    }

    @Test
    void shellWithRmRIsDenied() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"rm -r /home\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("recursive delete");
    }

    @Test
    void shellWithDelSlashSIsDenied() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"del /s /q *\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("recursive delete");
    }

    @Test
    void shellWithFormatIsDenied() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"format C:\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("disk format");
    }

    @Test
    void shellWithShutdownIsDenied() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"shutdown now\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("shutdown");
    }

    @Test
    void shellWithSystemctlIsDenied() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"systemctl restart nginx\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("systemctl");
    }

    @Test
    void shellWithKillIsDenied() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"kill -9 1234\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("kill");
    }

    @Test
    void shellWithSudoIsDenied() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"sudo apt update\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("sudo");
    }

    @Test
    void shellWithRedirectToJavaSourceIsDenied() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"echo bad > *.java\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("overwrite source");
    }

    @Test
    void benignShellCommandIsAllowed() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"ls -la\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void emptyArgsIsAllowed() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isTrue();
    }

    // --- bypass tests ---

    @Test
    void unicodeEscapedRmRfIsDenied() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"\\u0072\\u006d -rf /\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("recursive delete");
    }

    @Test
    void uppercaseRmRfIsDenied() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"RM -RF /\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("recursive delete");
    }

    @Test
    void multipleWhitespaceRmRfIsDenied() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"rm    -rf /\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("recursive delete");
    }

    @Test
    void unicodeEscapedSudoIsDenied() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"\\u0073\\u0075\\u0064\\u006f apt update\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("sudo");
    }

    // --- false-positive protection ---

    @Test
    void skillIsNotBlocked() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"echo skill check\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void pseudoIsNotBlocked() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"echo pseudo code\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void formatAsPartOfWordIsNotBlocked() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"echo formatted output\"}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void systemctlInsideEchoIsNotBlocked() {
        // Wait, "echo systemctl restart" should still be blocked because systemctl is a standalone word
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":\"echo systemctl restart nginx\"}");

        ToolExecutionDecision decision = policy.decide(call);

        // This SHOULD be blocked because systemctl is a real command
        assertThat(decision.allowed()).isFalse();
    }

    @Test
    void normalizeCommandMethod() {
        assertThat(DangerousCommandPolicy.normalizeCommand("  RM   -RF  /  ")).isEqualTo("rm -rf /");
        assertThat(DangerousCommandPolicy.normalizeCommand(null)).isEqualTo("");
    }

    @Test
    void invalidJsonIsAllowed() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{not json");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void nonStringCommandFieldIsAllowed() {
        ToolCall call = ToolCall.of("t1", "shell_command", "{\"command\":123}");

        ToolExecutionDecision decision = policy.decide(call);

        assertThat(decision.allowed()).isTrue();
    }
}
