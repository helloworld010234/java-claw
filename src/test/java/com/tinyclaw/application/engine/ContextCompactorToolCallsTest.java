package com.tinyclaw.application.engine;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import com.tinyclaw.domain.message.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextCompactorToolCallsTest {

    @Test
    void countsToolCallArgumentsInEstimateLength() {
        String bigArgs = "x".repeat(5000);
        ToolCall tc = ToolCall.of("t1", "write_file", bigArgs);
        Message assistant = Message.assistantWithToolCalls("short", List.of(tc));
        Message system = Message.system("sys");

        // maxChars=10, retainLast=0 -> nothing protected; assistant message
        // has content (5) + name (10) + args (5000) = 5015 chars, triggers compaction
        ContextCompactor compactor = new ContextCompactor(10, 0);
        List<Message> result = compactor.compact(system, List.of(assistant));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        Message compacted = result.get(1);
        assertThat(compacted.role()).isEqualTo(Role.ASSISTANT);
        // Tool calls must be preserved regardless of content collapse
        assertThat(compacted.toolCalls()).hasSize(1);
        assertThat(compacted.toolCalls().get(0).argumentsJson()).isEqualTo(bigArgs);
    }

    @Test
    void largeToolArgumentsTriggerCompaction() {
        String hugeArgs = "a".repeat(30000);
        ToolCall tc = ToolCall.of("t1", "write_file", hugeArgs);
        Message assistant = Message.assistantWithToolCalls("", List.of(tc));
        Message system = Message.system("sys");

        // Default maxChars=20000, so hugeArgs alone exceeds threshold
        ContextCompactor compactor = new ContextCompactor();
        List<Message> result = compactor.compact(system, List.of(assistant));

        // System preserved, assistant kept but tool calls must survive
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        Message compacted = result.get(1);
        assertThat(compacted.toolCalls()).hasSize(1);
        assertThat(compacted.toolCalls().get(0).argumentsJson()).isEqualTo(hugeArgs);
    }

    @Test
    void collapsesAssistantContentWhenLongButPreservesToolCalls() {
        String longContent = "r".repeat(1000);
        ToolCall tc = ToolCall.of("t1", "write_file", "{}");
        Message assistant = Message.assistantWithToolCalls(longContent, List.of(tc));
        Message system = Message.system("sys");

        // maxChars=10, retainLast=0 -> assistant not protected, content > 200 so collapsed
        ContextCompactor compactor = new ContextCompactor(10, 0);
        List<Message> result = compactor.compact(system, List.of(assistant));

        Message compacted = result.get(1);
        assertThat(compacted.content()).isEqualTo("...[early assistant reasoning collapsed]...");
        assertThat(compacted.toolCalls()).hasSize(1);
        assertThat(compacted.toolCalls().get(0).id()).isEqualTo("t1");
    }

    @Test
    void originalMessageNotModified() {
        String args = "x".repeat(5000);
        ToolCall tc = ToolCall.of("t1", "write_file", args);
        Message assistant = Message.assistantWithToolCalls("original content", List.of(tc));
        Message system = Message.system("sys");

        ContextCompactor compactor = new ContextCompactor(50, 0);
        compactor.compact(system, List.of(assistant));

        assertThat(assistant.content()).isEqualTo("original content");
        assertThat(assistant.toolCalls().get(0).argumentsJson()).isEqualTo(args);
    }
}
