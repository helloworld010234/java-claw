package com.tinyclaw.adapters.llm.springai;

import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.ToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiMessageMapperTest {

    @Test
    void mapsSystemMessage() {
        List<org.springframework.ai.chat.messages.Message> result =
            SpringAiMessageMapper.toSpringAiMessages(List.of(Message.system("sys prompt")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(result.get(0).getText()).isEqualTo("sys prompt");
    }

    @Test
    void mapsUserMessage() {
        List<org.springframework.ai.chat.messages.Message> result =
            SpringAiMessageMapper.toSpringAiMessages(List.of(Message.user("hello")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        assertThat(result.get(0).getText()).isEqualTo("hello");
    }

    @Test
    void mapsToolObservationToToolResponseMessage() {
        List<org.springframework.ai.chat.messages.Message> result =
            SpringAiMessageMapper.toSpringAiMessages(List.of(Message.toolObservation("tc1", "output data")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage trm = (ToolResponseMessage) result.get(0);
        assertThat(trm.getResponses()).hasSize(1);
        assertThat(trm.getResponses().get(0).id()).isEqualTo("tc1");
        assertThat(trm.getResponses().get(0).responseData()).isEqualTo("output data");
    }

    @Test
    void mapsAssistantWithToolCalls() {
        ToolCall tc = ToolCall.of("tc1", "write_file", "{\"path\":\"a.txt\"}");
        List<org.springframework.ai.chat.messages.Message> result =
            SpringAiMessageMapper.toSpringAiMessages(List.of(Message.assistantWithToolCalls("", List.of(tc))));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(AssistantMessage.class);
        AssistantMessage am = (AssistantMessage) result.get(0);
        assertThat(am.getToolCalls()).hasSize(1);
        assertThat(am.getToolCalls().get(0).id()).isEqualTo("tc1");
        assertThat(am.getToolCalls().get(0).name()).isEqualTo("write_file");
        assertThat(am.getToolCalls().get(0).arguments()).isEqualTo("{\"path\":\"a.txt\"}");
    }

    @Test
    void extractContentReturnsText() {
        AssistantMessage am = AssistantMessage.builder().content("hello").build();
        assertThat(SpringAiMessageMapper.extractContent(am)).isEqualTo("hello");
    }

    @Test
    void extractContentReturnsEmptyStringForNull() {
        AssistantMessage am = AssistantMessage.builder().content(null).build();
        assertThat(SpringAiMessageMapper.extractContent(am)).isEqualTo("");
    }

    @Test
    void extractToolCallsMapsCorrectly() {
        AssistantMessage am = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("t1", "function", "read_file", "{}")))
            .build();

        List<ToolCall> result = SpringAiMessageMapper.extractToolCalls(am);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("t1");
        assertThat(result.get(0).name()).isEqualTo("read_file");
        assertThat(result.get(0).argumentsJson()).isEqualTo("{}");
    }

    @Test
    void extractToolCallsReturnsEmptyListWhenNone() {
        AssistantMessage am = AssistantMessage.builder().content("hi").build();
        assertThat(SpringAiMessageMapper.extractToolCalls(am)).isEmpty();
    }
}
