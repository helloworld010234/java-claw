package com.tinyclaw.adapters.session;

import com.tinyclaw.domain.message.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemorySessionServiceTest {

    private InMemorySessionService service;

    @BeforeEach
    void setUp() {
        service = new InMemorySessionService();
    }

    @Test
    void returnsEmptyForUnknownSession() {
        assertThat(service.getWorkingMemory("unknown")).isEmpty();
    }

    @Test
    void appendsAndRetrievesMessages() {
        service.appendMessage("s1", Message.user("hello"));
        service.appendMessage("s1", Message.assistant("hi"));

        List<Message> memory = service.getWorkingMemory("s1");
        assertThat(memory).hasSize(2);
        assertThat(memory.get(0).content()).isEqualTo("hello");
        assertThat(memory.get(1).content()).isEqualTo("hi");
    }

    @Test
    void isolatesSessions() {
        service.appendMessage("s1", Message.user("A"));
        service.appendMessage("s2", Message.user("B"));

        assertThat(service.getWorkingMemory("s1")).hasSize(1);
        assertThat(service.getWorkingMemory("s2")).hasSize(1);
        assertThat(service.getWorkingMemory("s1").get(0).content()).isEqualTo("A");
        assertThat(service.getWorkingMemory("s2").get(0).content()).isEqualTo("B");
    }

    @Test
    void clearRemovesAllMessages() {
        service.appendMessage("s1", Message.user("hello"));
        service.clear();
        assertThat(service.getWorkingMemory("s1")).isEmpty();
    }

    @Test
    void returnedMemoryIsImmutable() {
        service.appendMessage("s1", Message.user("hello"));
        List<Message> memory = service.getWorkingMemory("s1");

        assertThatThrownBy(() -> memory.add(Message.assistant("x")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void replaceMessagesOverwritesExistingHistory() {
        service.appendMessage("s1", Message.user("old"));

        service.replaceMessages("s1", List.of(Message.user("new1"), Message.assistant("new2")));

        List<Message> memory = service.getWorkingMemory("s1");
        assertThat(memory).hasSize(2);
        assertThat(memory.get(0).content()).isEqualTo("new1");
        assertThat(memory.get(1).content()).isEqualTo("new2");
    }

    @Test
    void replaceMessagesIsIsolatedFromExternalListChanges() {
        List<Message> external = new ArrayList<>();
        external.add(Message.user("a"));
        service.replaceMessages("s1", external);
        external.add(Message.assistant("b"));

        List<Message> memory = service.getWorkingMemory("s1");
        assertThat(memory).hasSize(1);
    }

    @Test
    void replaceMessagesWithEmptyListClearsSession() {
        service.appendMessage("s1", Message.user("old"));
        service.replaceMessages("s1", List.of());

        assertThat(service.getWorkingMemory("s1")).isEmpty();
    }
}
