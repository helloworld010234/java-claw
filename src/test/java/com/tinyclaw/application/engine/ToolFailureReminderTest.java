package com.tinyclaw.application.engine;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ToolFailureReminderTest {

    private final ToolFailureReminder reminder = new ToolFailureReminder();
    private final ToolCall call = ToolCall.of("c1", "read_file", "{\"path\":\"x.txt\"}");

    @Test
    void noReminderBeforeThirdFailure() {
        ToolResult fail = ToolResult.failure("c1", "error");

        assertThat(reminder.onToolResult(call, fail)).isEmpty();
        assertThat(reminder.onToolResult(call, fail)).isEmpty();
    }

    @Test
    void reminderOnThirdFailure() {
        ToolResult fail = ToolResult.failure("c1", "error");
        reminder.onToolResult(call, fail);
        reminder.onToolResult(call, fail);

        Optional<Message> result = reminder.onToolResult(call, fail);

        assertThat(result).isPresent();
        Message msg = result.get();
        assertThat(msg.role()).isEqualTo(Role.USER);
        assertThat(msg.content()).contains("SYSTEM REMINDER").contains("read_file");
    }

    @Test
    void successResetsFailureCount() {
        ToolResult fail = ToolResult.failure("c1", "error");
        ToolResult success = ToolResult.success("c1", "ok");

        reminder.onToolResult(call, fail);
        reminder.onToolResult(call, fail);
        reminder.onToolResult(call, success);

        assertThat(reminder.onToolResult(call, fail)).isEmpty();
    }

    @Test
    void differentFingerprintStartsSeparateCount() {
        ToolCall other = ToolCall.of("c2", "read_file", "{\"path\":\"y.txt\"}");
        ToolResult fail = ToolResult.failure("c1", "error");

        reminder.onToolResult(call, fail);
        reminder.onToolResult(call, fail);
        reminder.onToolResult(other, fail);

        assertThat(reminder.onToolResult(call, fail)).isPresent();
        assertThat(reminder.onToolResult(other, fail)).isEmpty();
    }

    @Test
    void successIsSilent() {
        assertThat(reminder.onToolResult(call, ToolResult.success("c1", "ok"))).isEmpty();
    }
}
