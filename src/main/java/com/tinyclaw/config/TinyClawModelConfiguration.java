package com.tinyclaw.config;

import com.tinyclaw.adapters.llm.ObservedLlmGateway;
import com.tinyclaw.adapters.llm.RetryingLlmGateway;
import com.tinyclaw.adapters.llm.TimeoutLlmGateway;
import com.tinyclaw.adapters.llm.springai.SpringAiLlmGateway;
import com.tinyclaw.ports.llm.LlmGateway;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Conditional configuration for real LLM integration via Spring AI.
 *
 * <p>Only active when {@code tiny-claw.model.enabled=true}. Creates the production
 * {@link LlmGateway} bean wrapped with timeout, retry, and usage/cost observation.</p>
 *
 * <p>If the API key is missing, the gateway fail-fasts on the first call with a clear
 * {@link com.tinyclaw.ports.llm.LlmException} instead of making a real network request.</p>
 *
 * <p>Wrapper chain (inner to outer):</p>
 * <ol>
 *   <li>{@link SpringAiLlmGateway} for the Spring AI adapter</li>
 *   <li>{@link TimeoutLlmGateway} for caller-visible timeout enforcement</li>
 *   <li>{@link RetryingLlmGateway} for transient failure retries</li>
 *   <li>{@link ObservedLlmGateway} for metrics and cost observation</li>
 * </ol>
 */
@Configuration
public class TinyClawModelConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "tiny-claw.model", name = "enabled", havingValue = "true")
    public ExecutorService llmCallExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    @ConditionalOnProperty(prefix = "tiny-claw.model", name = "enabled", havingValue = "true")
    public LlmGateway realLlmGateway(
            TinyClawModelProperties properties,
            MeterRegistry meterRegistry,
            ExecutorService llmCallExecutor) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            LlmGateway failFastGateway = request -> {
                throw new com.tinyclaw.ports.llm.LlmException(
                    "Real LLM engine requires an API key. "
                        + "Set tiny-claw.model.api-key or LLM_API_KEY environment variable."
                );
            };
            return new ObservedLlmGateway(failFastGateway, meterRegistry, properties);
        }

        ChatModel chatModel = createChatModel(properties);

        LlmGateway springAiGateway = new SpringAiLlmGateway(chatModel, properties);
        LlmGateway timeoutGateway = new TimeoutLlmGateway(springAiGateway, properties.getRequestTimeoutSeconds(), llmCallExecutor);
        LlmGateway retryingGateway = new RetryingLlmGateway(timeoutGateway, properties);

        return new ObservedLlmGateway(retryingGateway, meterRegistry, properties);
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
