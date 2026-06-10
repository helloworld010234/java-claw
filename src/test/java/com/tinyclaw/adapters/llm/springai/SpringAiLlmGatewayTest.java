package com.tinyclaw.adapters.llm.springai;

import com.tinyclaw.config.TinyClawModelProperties;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.ports.llm.LlmErrorType;
import com.tinyclaw.ports.llm.LlmException;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmRequestOptions;
import com.tinyclaw.ports.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiLlmGatewayTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final TinyClawModelProperties properties = new TinyClawModelProperties();
    private final SpringAiLlmGateway gateway = new SpringAiLlmGateway(chatModel, properties);

    @Test
    void returnsAssistantContent() {
        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("Hello").build())))
        );

        LlmRequest request = new LlmRequest("test", List.of(Message.user("hi")), List.of(), LlmRequestOptions.defaults());
        LlmResponse response = gateway.generate(request);

        assertThat(response.content()).isEqualTo("Hello");
        assertThat(response.hasToolCalls()).isFalse();
        assertThat(response.usage()).isNull();
    }

    @Test
    void returnsToolCalls() {
        AssistantMessage am = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("t1", "function", "write_file", "{}")))
            .build();
        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(am)))
        );

        LlmRequest request = new LlmRequest("test", List.of(Message.user("hi")), List.of(), LlmRequestOptions.defaults());
        LlmResponse response = gateway.generate(request);

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).name()).isEqualTo("write_file");
    }

    @Test
    void extractsUsageWhenAvailable() {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
            .usage(new DefaultUsage(10, 20, 30, null))
            .build();
        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("ok").build())), metadata)
        );

        LlmRequest request = new LlmRequest("test", List.of(Message.user("hi")), List.of(), LlmRequestOptions.defaults());
        LlmResponse response = gateway.generate(request);

        assertThat(response.usage()).isNotNull();
        assertThat(response.usage().promptTokens()).isEqualTo(10);
        assertThat(response.usage().completionTokens()).isEqualTo(20);
        assertThat(response.usage().totalTokens()).isEqualTo(30);
    }

    @Test
    void returnsNullUsageWhenNotProvided() {
        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("ok").build())))
        );

        LlmRequest request = new LlmRequest("test", List.of(Message.user("hi")), List.of(), LlmRequestOptions.defaults());
        LlmResponse response = gateway.generate(request);

        assertThat(response.usage()).isNull();
    }

    @Test
    void passesToolsToChatModel() {
        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("ok").build())))
        );

        ToolDefinition tool = new ToolDefinition("read_file", "Reads a file", "{\"type\":\"object\"}");
        LlmRequest request = new LlmRequest("test", List.of(Message.user("hi")), List.of(tool), LlmRequestOptions.defaults());
        gateway.generate(request);
    }

    @Test
    void throwsLlmExceptionOnFailure() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("network error"));

        LlmRequest request = new LlmRequest("test", List.of(Message.user("hi")), List.of(), LlmRequestOptions.defaults());
        assertThatThrownBy(() -> gateway.generate(request))
            .isInstanceOf(LlmException.class)
            .hasMessageContaining("network error");
    }

    @Test
    void rethrowsLlmExceptionWithoutWrapping() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new LlmException("already wrapped"));

        LlmRequest request = new LlmRequest("test", List.of(Message.user("hi")), List.of(), LlmRequestOptions.defaults());
        assertThatThrownBy(() -> gateway.generate(request))
            .isInstanceOf(LlmException.class)
            .hasMessageContaining("already wrapped");
    }

    @Test
    void usesConfiguredModelNameWhenRequestModelIsBlank() {
        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("Hello").build())))
        );

        TinyClawModelProperties props = new TinyClawModelProperties();
        props.setName("custom-model-from-config");
        SpringAiLlmGateway configuredGateway = new SpringAiLlmGateway(chatModel, props);

        LlmRequest request = new LlmRequest("", List.of(Message.user("hi")), List.of(), LlmRequestOptions.defaults());
        configuredGateway.generate(request);

        org.mockito.ArgumentCaptor<Prompt> promptCaptor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Prompt capturedPrompt = promptCaptor.getValue();
        assertThat(capturedPrompt.getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) capturedPrompt.getOptions();
        assertThat(options.getModel()).isEqualTo("custom-model-from-config");
    }

    @Test
    void usesRequestModelNameWhenProvided() {
        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("Hello").build())))
        );

        TinyClawModelProperties props = new TinyClawModelProperties();
        props.setName("config-model");
        SpringAiLlmGateway configuredGateway = new SpringAiLlmGateway(chatModel, props);

        LlmRequest request = new LlmRequest("override-model", List.of(Message.user("hi")), List.of(), LlmRequestOptions.defaults());
        configuredGateway.generate(request);

        org.mockito.ArgumentCaptor<Prompt> promptCaptor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Prompt capturedPrompt = promptCaptor.getValue();
        assertThat(capturedPrompt.getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) capturedPrompt.getOptions();
        assertThat(options.getModel()).isEqualTo("override-model");
    }

    @Test
    void classifiesResourceAccessExceptionAsTransientNetwork() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new ResourceAccessException("I/O error"));

        LlmRequest request = new LlmRequest("test", List.of(Message.user("hi")), List.of(), LlmRequestOptions.defaults());
        assertThatThrownBy(() -> gateway.generate(request))
            .isInstanceOf(LlmException.class)
            .satisfies(e -> assertThat(((LlmException) e).getErrorType()).isEqualTo(LlmErrorType.TRANSIENT_NETWORK));
    }

    @Test
    void classifiesHttp401AsAuthentication() {
        when(chatModel.call(any(Prompt.class))).thenThrow(
            HttpClientErrorException.create(org.springframework.http.HttpStatus.UNAUTHORIZED,
                "Unauthorized", new org.springframework.http.HttpHeaders(), new byte[0], java.nio.charset.StandardCharsets.UTF_8));

        LlmRequest request = new LlmRequest("test", List.of(Message.user("hi")), List.of(), LlmRequestOptions.defaults());
        assertThatThrownBy(() -> gateway.generate(request))
            .isInstanceOf(LlmException.class)
            .satisfies(e -> assertThat(((LlmException) e).getErrorType()).isEqualTo(LlmErrorType.AUTHENTICATION));
    }

    @Test
    void classifiesHttp429AsRateLimit() {
        when(chatModel.call(any(Prompt.class))).thenThrow(
            HttpClientErrorException.create(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests", new org.springframework.http.HttpHeaders(), new byte[0], java.nio.charset.StandardCharsets.UTF_8));

        LlmRequest request = new LlmRequest("test", List.of(Message.user("hi")), List.of(), LlmRequestOptions.defaults());
        assertThatThrownBy(() -> gateway.generate(request))
            .isInstanceOf(LlmException.class)
            .satisfies(e -> assertThat(((LlmException) e).getErrorType()).isEqualTo(LlmErrorType.RATE_LIMIT));
    }

    @Test
    void supportsDeepseekV4FlashModelName() {
        when(chatModel.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("Hello").build())))
        );

        TinyClawModelProperties props = new TinyClawModelProperties();
        props.setName("deepseek-v4-flash");
        SpringAiLlmGateway configuredGateway = new SpringAiLlmGateway(chatModel, props);

        LlmRequest request = new LlmRequest("", List.of(Message.user("hi")), List.of(), LlmRequestOptions.defaults());
        configuredGateway.generate(request);

        org.mockito.ArgumentCaptor<Prompt> promptCaptor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        OpenAiChatOptions options = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("deepseek-v4-flash");
    }
}
