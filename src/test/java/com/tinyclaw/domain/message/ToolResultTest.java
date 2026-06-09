package com.tinyclaw.domain.message;

import com.tinyclaw.domain.common.TinyClawDomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolResultTest {

    @Test
    void successHasErrorFalse() {
        ToolResult result = ToolResult.success("call-1", "file content");
        assertThat(result.toolCallId()).isEqualTo("call-1");
        assertThat(result.output()).isEqualTo("file content");
        assertThat(result.error()).isFalse();
    }

    @Test
    void failureHasErrorTrue() {
        ToolResult result = ToolResult.failure("call-1", "permission denied");
        assertThat(result.toolCallId()).isEqualTo("call-1");
        assertThat(result.output()).isEqualTo("permission denied");
        assertThat(result.error()).isTrue();
    }

    @Test
    void successWithNullOutputUsesEmptyString() {
        ToolResult result = ToolResult.success("call-1", null);
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).isFalse();
    }

    @Test
    void failureWithNullOutputUsesEmptyString() {
        ToolResult result = ToolResult.failure("call-1", null);
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).isTrue();
    }

    @Test
    void nullToolCallIdThrows() {
        assertThatThrownBy(() -> ToolResult.success(null, "ok"))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("toolCallId");
    }

    @Test
    void blankToolCallIdThrows() {
        assertThatThrownBy(() -> ToolResult.failure("  ", "error"))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("toolCallId");
    }
}
