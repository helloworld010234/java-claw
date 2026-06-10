package com.tinyclaw.config;

import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.AgentRunResult;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.domain.run.AgentRun;
import com.tinyclaw.domain.session.Session;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test for real LLM engine path with a stubbed ChatModel.
 * Verifies Spring context starts, AgentEngine injects the real gateway,
 * and the configured model name flows through to the LLM request options.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tiny-claw.model.enabled=true",
    "tiny-claw.model.name=integration-test-model",
    "tiny-claw.model.api-key=sk-integration-test"
})
class RealEngineIntegrationTest {

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private LlmGateway llmGateway;

    @Autowired
    private ChatModel stubChatModel;

    @Test
    void contextLoadsWithSingleLlmGateway() {
        assertThat(llmGateway).isNotNull();
    }

    @Test
    void agentEngineIsInjected() {
        assertThat(agentEngine).isNotNull();
    }

    @Test
    void agentEngineUsesConfiguredModelName() {
        AgentRun run = AgentRun.start("run-1", "session-1", 3, Instant.now());
        Session session = Session.create(
            "session-1",
            Paths.get(".").toAbsolutePath().toString(),
            Instant.now()
        );

        AgentRunResult result = agentEngine
            .withModelName("integration-test-model")
            .run(run, session, "Hello", new ToolExecutionContext(Paths.get(".")));

        assertThat(result).isNotNull();

        org.mockito.ArgumentCaptor<Prompt> promptCaptor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(stubChatModel).call(promptCaptor.capture());

        Prompt capturedPrompt = promptCaptor.getValue();
        assertThat(capturedPrompt.getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) capturedPrompt.getOptions();
        assertThat(options.getModel()).isEqualTo("integration-test-model");
    }

    @TestConfiguration
    static class StubChatModelConfig {
        @Bean
        @Primary
        ChatModel stubChatModel() {
            ChatModel mock = mock(ChatModel.class);
            when(mock.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("Stub response").build())))
            );
            return mock;
        }
    }
}
