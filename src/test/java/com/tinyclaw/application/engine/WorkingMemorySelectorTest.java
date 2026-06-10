package com.tinyclaw.application.engine;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.message.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkingMemorySelectorTest {

    private final WorkingMemorySelector selector = new WorkingMemorySelector();

    @Test
    void returnsEmptyForEmptyInput() {
        assertThat(selector.select(List.of(), 10)).isEmpty();
    }

    @Test
    void returnsLastNMessages() {
        List<Message> messages = List.of(
            Message.user("a"),
            Message.user("b"),
            Message.user("c"),
            Message.user("d")
        );

        List<Message> result = selector.select(messages, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("c");
        assertThat(result.get(1).content()).isEqualTo("d");
    }

    @Test
    void limitZeroReturnsAllMessages() {
        List<Message> messages = List.of(
            Message.user("a"),
            Message.user("b")
        );

        List<Message> result = selector.select(messages, 0);

        assertThat(result).hasSize(2);
    }

    @Test
    void filtersOutSystemMessages() {
        List<Message> messages = List.of(
            Message.system("system prompt"),
            Message.user("hello"),
            Message.assistant("hi")
        );

        List<Message> result = selector.select(messages, 10);

        assertThat(result).hasSize(2);
        assertThat(result).noneMatch(m -> m.role() == Role.SYSTEM);
    }

    @Test
    void dropsLeadingOrphanToolObservations() {
        // Window lands on two trailing tool observations with no matching assistant in the window.
        List<Message> messages = List.of(
            Message.user("user-1"),
            Message.assistantWithToolCalls("", List.of(ToolCall.of("t1", "read_file", "{}"))),
            Message.toolObservation("t1", "out-1"),
            Message.toolObservation("t2", "out-2")
        );

        List<Message> result = selector.select(messages, 2);

        assertThat(result).isEmpty();
    }

    @Test
    void keepsNonOrphanMessageAfterDropping() {
        // Window lands on orphan observation followed by a real user message.
        List<Message> messages = List.of(
            Message.assistantWithToolCalls("", List.of(ToolCall.of("t1", "read_file", "{}"))),
            Message.toolObservation("t1", "out-1"),
            Message.user("continue")
        );

        List<Message> result = selector.select(messages, 2);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).role()).isEqualTo(Role.USER);
        assertThat(result.get(0).content()).isEqualTo("continue");
    }

    @Test
    void injectsCheckpointWhenFirstMessageIsNotUser() {
        List<Message> messages = List.of(
            Message.assistant("previous answer")
        );

        List<Message> result = selector.select(messages, 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).role()).isEqualTo(Role.USER);
        assertThat(result.get(0).content()).isEqualTo(WorkingMemorySelector.CHECKPOINT_CONTENT);
        assertThat(result.get(1).role()).isEqualTo(Role.ASSISTANT);
    }

    @Test
    void returnedListIsImmutable() {
        List<Message> messages = List.of(Message.user("x"));

        List<Message> result = selector.select(messages, 10);

        assertThat(result).hasSize(1);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> result.add(Message.user("y")));
    }
}
