package com.tinyclaw.config;

import com.tinyclaw.adapters.llm.ObservedLlmGateway;
import com.tinyclaw.adapters.llm.springai.SpringAiLlmGateway;
import com.tinyclaw.ports.llm.LlmGateway;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/**
 * Conditional configuration for real LLM integration via Spring AI.
 *
 * <p>Only active when {@code tiny-claw.model.enabled=true}. Creates the production
 * {@link LlmGateway} bean wrapped with usage/cost observation.</p>
 */
@Configuration
public class TinyClawModelConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "tiny-claw.model", name = "enabled", havingValue = "true")
    public LlmGateway realLlmGateway(
            ObjectProvider<ChatModel> chatModelProvider,
            TinyClawModelProperties properties,
            MeterRegistry meterRegistry) {
        ChatModel chatModel = chatModelProvider.getIfAvailable(() -> createChatModel(properties));
        LlmGateway springAiGateway = new SpringAiLlmGateway(chatModel, properties);
        return new ObservedLlmGateway(springAiGateway, meterRegistry, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "tiny-claw.model", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel tinyClawChatModel(TinyClawModelProperties properties) {
        return createChatModel(properties);
    }

    private ChatModel createChatModel(TinyClawModelProperties properties) {
        String apiKey = properties.getApiKey();
        String baseUrl = properties.getBaseUrl();
        String modelName = properties.getName();

        OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(apiKey);
        if (baseUrl != null && !baseUrl.isBlank()) {
            apiBuilder = apiBuilder.baseUrl(baseUrl);
        }
        OpenAiApi openAiApi = apiBuilder.build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(modelName)
            .temperature(properties.getTemperature())
            .maxTokens(properties.getMaxTokens())
            .build();

        return OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(options)
            .toolCallingManager(ToolCallingManager.builder().build())
            .retryTemplate(new RetryTemplate())
            .observationRegistry(ObservationRegistry.NOOP)
            .build();
    }
}
