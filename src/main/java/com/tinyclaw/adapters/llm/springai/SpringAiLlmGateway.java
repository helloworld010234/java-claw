package com.tinyclaw.adapters.llm.springai;

import com.tinyclaw.config.TinyClawModelProperties;
import com.tinyclaw.domain.message.Message;
import com.tinyclaw.domain.message.ToolCall;
import com.tinyclaw.domain.message.ToolDefinition;
import com.tinyclaw.domain.message.Usage;
import com.tinyclaw.ports.llm.LlmException;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmRequest;
import com.tinyclaw.ports.llm.LlmResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;

/**
 * Production adapter that delegates LLM calls to Spring AI {@link ChatModel}.
 *
 * <p>All Spring AI types are confined to this adapter package.</p>
 */
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatModel chatModel;
    private final String defaultModel;

    public SpringAiLlmGateway(ChatModel chatModel, TinyClawModelProperties properties) {
        if (chatModel == null) {
            throw new IllegalArgumentException("chatModel must not be null");
        }
        this.chatModel = chatModel;
        this.defaultModel = properties != null && properties.getName() != null ? properties.getName() : "default";
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        try {
            List<org.springframework.ai.chat.messages.Message> springMessages =
                SpringAiMessageMapper.toSpringAiMessages(request.messages());

            OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(request.model() != null && !request.model().isBlank() ? request.model() : defaultModel)
                .temperature(request.options().temperature())
                .maxTokens(request.options().maxTokens())
                .internalToolExecutionEnabled(false)
                .tools(SpringAiToolMapper.toOpenAiTools(request.tools()))
                .build();

            Prompt prompt = new Prompt(springMessages, options);
            ChatResponse chatResponse = chatModel.call(prompt);

            return mapResponse(chatResponse);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Spring AI LLM call failed: " + e.getMessage(), e);
        }
    }

    private LlmResponse mapResponse(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResults() == null || chatResponse.getResults().isEmpty()) {
            return new LlmResponse("", List.of(), null);
        }

        Generation generation = chatResponse.getResult();
        AssistantMessage assistantMessage = generation.getOutput();

        String content = SpringAiMessageMapper.extractContent(assistantMessage);
        List<ToolCall> toolCalls = SpringAiMessageMapper.extractToolCalls(assistantMessage);
        Usage usage = extractUsage(chatResponse.getMetadata());

        return new LlmResponse(content, toolCalls, usage);
    }

    private Usage extractUsage(ChatResponseMetadata metadata) {
        if (metadata == null || metadata.getUsage() == null) {
            return null;
        }
        org.springframework.ai.chat.metadata.Usage springUsage = metadata.getUsage();
        Integer promptTokens = springUsage.getPromptTokens();
        Integer completionTokens = springUsage.getCompletionTokens();
        if (promptTokens == null || completionTokens == null) {
            return null;
        }
        // Some providers return Usage[0,0] when not tracking; treat as absent
        if (promptTokens == 0 && completionTokens == 0) {
            return null;
        }
        return new Usage(promptTokens, completionTokens);
    }
}
