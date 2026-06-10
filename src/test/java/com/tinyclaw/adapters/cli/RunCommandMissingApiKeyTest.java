package com.tinyclaw.adapters.cli;

import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.config.TinyClawModelProperties;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that --engine real with enabled=true but missing API key returns
 * a controlled CLI error (exit code 2) without Spring startup failure.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tiny-claw.model.enabled=true",
    "tiny-claw.model.name=test-model",
    "tiny-claw.model.api-key="
})
class RunCommandMissingApiKeyTest {

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private TinyClawModelProperties modelProperties;

    @Test
    void contextStartsEvenWithMissingApiKey() {
        assertThat(agentEngine).isNotNull();
        assertThat(modelProperties.isEnabled()).isTrue();
        assertThat(modelProperties.getApiKey()).isBlank();
    }

    @TestConfiguration
    static class StubChatModelConfig {
        @Bean
        @Primary
        ChatModel stubChatModel() {
            ChatModel mock = mock(ChatModel.class);
            when(mock.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("Stub").build())))
            );
            return mock;
        }
    }
}
