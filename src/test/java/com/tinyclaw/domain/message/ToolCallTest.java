package com.tinyclaw.domain.message;

import com.tinyclaw.domain.common.TinyClawDomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCallTest {

    @Test
    void ofCreatesToolCall() {
        ToolCall tc = ToolCall.of("call-1", "read_file", "{\"path\":\"/tmp/test\"}");
        assertThat(tc.id()).isEqualTo("call-1");
        assertThat(tc.name()).isEqualTo("read_file");
        assertThat(tc.argumentsJson()).isEqualTo("{\"path\":\"/tmp/test\"}");
    }

    @Test
    void nullIdThrows() {
        assertThatThrownBy(() -> ToolCall.of(null, "read_file", "{}"))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("id");
    }

    @Test
    void blankIdThrows() {
        assertThatThrownBy(() -> ToolCall.of("  ", "read_file", "{}"))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("id");
    }

    @Test
    void nullNameThrows() {
        assertThatThrownBy(() -> ToolCall.of("call-1", null, "{}"))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("name");
    }

    @Test
    void blankNameThrows() {
        assertThatThrownBy(() -> ToolCall.of("call-1", "", "{}"))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("name");
    }

    @Test
    void nullArgumentsJsonThrows() {
        assertThatThrownBy(() -> ToolCall.of("call-1", "read_file", null))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("argumentsJson");
    }

    @Test
    void blankArgumentsJsonThrows() {
        assertThatThrownBy(() -> ToolCall.of("call-1", "read_file", "   "))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("argumentsJson");
    }
}
