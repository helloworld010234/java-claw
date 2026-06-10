package com.tinyclaw.adapters.llm.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.domain.message.ToolDefinition;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;
import java.util.Map;

/**
 * Maps between TinyClaw domain {@link ToolDefinition} and Spring AI OpenAI tool schema.
 */
public final class SpringAiToolMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_SCHEMA = "{\"type\":\"object\"}";

    private SpringAiToolMapper() {
    }

    /**
     * Converts a list of domain tool definitions to OpenAI FunctionTool objects.
     *
     * @param tools domain tool definitions
     * @return OpenAI function tools
     */
    public static List<OpenAiApi.FunctionTool> toOpenAiTools(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream()
            .map(SpringAiToolMapper::toOpenAiTool)
            .toList();
    }

    private static OpenAiApi.FunctionTool toOpenAiTool(ToolDefinition tool) {
        OpenAiApi.FunctionTool functionTool = new OpenAiApi.FunctionTool();
        functionTool.setType(OpenAiApi.FunctionTool.Type.FUNCTION);

        String schema = validateSchema(tool.inputSchemaJson());
        // Spring AI constructor order is (description, name, jsonSchema)
        OpenAiApi.FunctionTool.Function function = new OpenAiApi.FunctionTool.Function(
            tool.description(), tool.name(), schema
        );
        functionTool.setFunction(function);
        return functionTool;
    }

    private static String validateSchema(String jsonSchema) {
        if (jsonSchema == null || jsonSchema.isBlank()) {
            return DEFAULT_SCHEMA;
        }
        try {
            OBJECT_MAPPER.readTree(jsonSchema);
            return jsonSchema;
        } catch (Exception e) {
            return DEFAULT_SCHEMA;
        }
    }
}
