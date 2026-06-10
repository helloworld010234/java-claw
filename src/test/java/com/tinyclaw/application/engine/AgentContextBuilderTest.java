package com.tinyclaw.application.engine;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContextBuilderTest {

    private final PromptComposer promptComposer = new PromptComposer();
    private final WorkingMemorySelector selector = new WorkingMemorySelector();
    private final ContextCompactor compactor = new ContextCompactor();
    private final AgentContextBuilder builder = new AgentContextBuilder(promptComposer, selector, compactor);

    @Test
    void startsWithSystemPrompt() {
        List<Message> sessionMessages = List.of(Message.user("hello"));

        List<Message> context = builder.build("/workspace", sessionMessages, 10);

        assertThat(context).hasSizeGreaterThanOrEqualTo(1);
        assertThat(context.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(context.get(0).content()).contains("TinyClaw").contains("/workspace");
    }

    @Test
    void includesWorkingMemoryAfterSystemPrompt() {
        List<Message> sessionMessages = List.of(
            Message.user("first"),
            Message.assistant("second")
        );

        List<Message> context = builder.build("/workspace", sessionMessages, 10);

        assertThat(context).hasSize(3);
        assertThat(context.get(1).content()).isEqualTo("first");
        assertThat(context.get(2).content()).isEqualTo("second");
    }

    @Test
    void appliesCompactionWhenThresholdExceeded() {
        String big = "x".repeat(10000);
        List<Message> sessionMessages = new ArrayList<>();
        // Add enough early assistant messages so they fall outside the protected zone.
        for (int i = 0; i < 3; i++) {
            sessionMessages.add(Message.user("marker-" + i));
            sessionMessages.add(Message.assistant(big));
        }
        // Recent messages inside protected zone
        sessionMessages.add(Message.user("recent-1"));
        sessionMessages.add(Message.user("recent-2"));

        List<Message> context = builder.build("/workspace", sessionMessages, 20);

        assertThat(context.get(0).role()).isEqualTo(Role.SYSTEM);
        // The first early big message (index 1 in working memory) should be compacted
        assertThat(context.get(2).role()).isEqualTo(Role.ASSISTANT);
        assertThat(context.get(2).content().length()).isLessThan(big.length());
        // Recent messages should stay intact
        assertThat(context.get(context.size() - 2).content()).isEqualTo("recent-1");
        assertThat(context.get(context.size() - 1).content()).isEqualTo("recent-2");
    }

    @Test
    void respectsWorkingMemoryLimit() {
        List<Message> sessionMessages = List.of(
            Message.user("a"),
            Message.user("b"),
            Message.user("c")
        );

        List<Message> context = builder.build("/workspace", sessionMessages, 2);

        assertThat(context).hasSize(3); // system + 2 working memory messages
        assertThat(context.get(1).content()).isEqualTo("b");
        assertThat(context.get(2).content()).isEqualTo("c");
    }
}
