package com.tinyclaw.domain.message;

import com.tinyclaw.domain.common.TinyClawDomainException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageTest {

    @Test
    void systemMessageFactory() {
        Message msg = Message.system("You are a helpful assistant");
        assertThat(msg.role()).isEqualTo(Role.SYSTEM);
        assertThat(msg.content()).isEqualTo("You are a helpful assistant");
        assertThat(msg.toolCalls()).isEmpty();
        assertThat(msg.toolCallId()).isNull();
        assertThat(msg.usage()).isNull();
    }

    @Test
    void userMessageFactory() {
        Message msg = Message.user("Hello");
        assertThat(msg.role()).isEqualTo(Role.USER);
        assertThat(msg.content()).isEqualTo("Hello");
        assertThat(msg.toolCalls()).isEmpty();
    }

    @Test
    void assistantMessageFactory() {
        Message msg = Message.assistant("Hi there");
        assertThat(msg.role()).isEqualTo(Role.ASSISTANT);
        assertThat(msg.content()).isEqualTo("Hi there");
        assertThat(msg.toolCalls()).isEmpty();
    }

    @Test
    void assistantWithToolCallsFactory() {
        ToolCall tc = ToolCall.of("call-1", "read_file", "{}");
        Message msg = Message.assistantWithToolCalls("Let me read that", List.of(tc));
        assertThat(msg.role()).isEqualTo(Role.ASSISTANT);
        assertThat(msg.content()).isEqualTo("Let me read that");
        assertThat(msg.toolCalls()).hasSize(1);
        assertThat(msg.toolCalls().get(0).id()).isEqualTo("call-1");
    }

    @Test
    void assistantWithToolCallsEmptyListThrows() {
        assertThatThrownBy(() -> Message.assistantWithToolCalls("test", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolCalls must not be null or empty");
    }

    @Test
    void assistantWithToolCallsNullListThrows() {
        assertThatThrownBy(() -> Message.assistantWithToolCalls("test", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolCalls must not be null or empty");
    }

    @Test
    void toolObservationFactory() {
        Message msg = Message.toolObservation("call-1", "file content");
        assertThat(msg.role()).isEqualTo(Role.USER);
        assertThat(msg.content()).isEqualTo("file content");
        assertThat(msg.toolCallId()).isEqualTo("call-1");
        assertThat(msg.toolCalls()).isEmpty();
    }

    @Test
    void toolObservationBlankToolCallIdThrows() {
        assertThatThrownBy(() -> Message.toolObservation("  ", "output"))
                .isInstanceOf(TinyClawDomainException.class)
                .hasMessageContaining("toolCallId");
    }

    @Test
    void toolCallsAreDefensivelyCopied() {
        List<ToolCall> original = new ArrayList<>();
        original.add(ToolCall.of("call-1", "read_file", "{}"));
        Message msg = Message.assistantWithToolCalls("test", original);
        original.add(ToolCall.of("call-2", "write_file", "{}"));
        assertThat(msg.toolCalls()).hasSize(1);
    }

    @Test
    void returnedToolCallsListIsImmutable() {
        ToolCall tc = ToolCall.of("call-1", "read_file", "{}");
        Message msg = Message.assistantWithToolCalls("test", List.of(tc));
        assertThatThrownBy(() -> msg.toolCalls().add(ToolCall.of("call-2", "write_file", "{}")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullContentHandledGracefully() {
        Message msg = Message.system(null);
        assertThat(msg.content()).isEmpty();
    }
}
