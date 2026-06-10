package com.tinyclaw.application.engine;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.message.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextCompactorTest {

    private final ContextCompactor compactor = new ContextCompactor();

    @Test
    void returnsCopyWhenUnderThreshold() {
        Message system = Message.system("sys");
        Message user = Message.user("hi");
        List<Message> workingMemory = List.of(user);

        List<Message> result = compactor.compact(system, workingMemory);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(0).content()).isEqualTo("sys");
        assertThat(result.get(1).role()).isEqualTo(Role.USER);
        assertThat(result.get(1).content()).isEqualTo("hi");
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test
    void preservesSystemMessage() {
        Message system = Message.system("important system prompt");
        List<Message> workingMemory = longWorkingMemory();

        List<Message> result = compactor.compact(system, workingMemory);

        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(0).content()).isEqualTo(system.content());
    }

    @Test
    void doesNotModifyOriginalMessages() {
        String original = "a".repeat(5000);
        Message system = Message.system("sys");
        Message observation = Message.toolObservation("t1", original);

        compactor.compact(system, List.of(observation));

        assertThat(observation.content()).isEqualTo(original);
    }

    @Test
    void truncatesEarlyLongToolObservation() {
        Message system = Message.system("sys");
        String longOutput = "x".repeat(500);
        Message earlyObservation = Message.toolObservation("t1", longOutput);
        Message recentUser = Message.user("recent");
        List<Message> workingMemory = List.of(earlyObservation, recentUser);

        // retain last 1 -> only recentUser is protected; earlyObservation should be compacted
        List<Message> result = new ContextCompactor(1, 1).compact(system, workingMemory);

        Message compacted = result.get(1);
        assertThat(compacted.role()).isEqualTo(Role.USER);
        assertThat(compacted.toolCallId()).isEqualTo("t1");
        assertThat(compacted.content()).contains("early tool output truncated").contains("500");
        assertThat(compacted.content().length()).isLessThan(longOutput.length());
    }

    @Test
    void keepsHeadAndTailForRecentLongToolObservation() {
        Message system = Message.system("sys");
        String head = "H".repeat(500);
        String middle = "M".repeat(5000);
        String tail = "T".repeat(500);
        Message observation = Message.toolObservation("t1", head + middle + tail);
        List<Message> workingMemory = List.of(observation);

        // retain last 1 protects the observation; threshold 1 forces truncation
        List<Message> result = new ContextCompactor(1, 1).compact(system, workingMemory);

        Message compacted = result.get(1);
        assertThat(compacted.content()).startsWith(head);
        assertThat(compacted.content()).endsWith(tail);
        assertThat(compacted.content()).contains("middle " + middle.length() + " chars truncated");
    }

    @Test
    void collapsesEarlyLongAssistantContent() {
        Message system = Message.system("sys");
        String longReasoning = "r".repeat(1000);
        List<Message> workingMemory = List.of(
            Message.user("u1"),
            Message.assistant(longReasoning),
            Message.user("u2")
        );

        List<Message> result = new ContextCompactor(1, 1).compact(system, workingMemory);

        Message compacted = result.get(result.size() - 2);
        assertThat(compacted.role()).isEqualTo(Role.ASSISTANT);
        assertThat(compacted.content()).isEqualTo("...[early assistant reasoning collapsed]...");
    }

    @Test
    void preservesAssistantToolCallsWhenCollapsing() {
        Message system = Message.system("sys");
        ToolCall call = ToolCall.of("t1", "write_file", "{}");
        Message assistant = Message.assistantWithToolCalls("r".repeat(1000), List.of(call));
        List<Message> workingMemory = List.of(Message.user("u1"), assistant);

        List<Message> result = new ContextCompactor(1, 1).compact(system, workingMemory);

        Message compacted = result.get(result.size() - 1);
        assertThat(compacted.toolCalls()).hasSize(1);
        assertThat(compacted.toolCalls().get(0).id()).isEqualTo("t1");
    }

    private List<Message> longWorkingMemory() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("line ").append(i).append(" ");
        }
        String big = sb.toString();
        return List.of(
            Message.user(big),
            Message.assistant(big),
            Message.user("recent")
        );
    }
}
