package com.tinyclaw.adapters.llm.springai;

import com.tinyclaw.domain.message.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiToolMapperTest {

    @Test
    void mapsToolDefinitionToOpenAiFunctionTool() {
        ToolDefinition tool = new ToolDefinition("read_file", "Reads a file", "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}");

        List<OpenAiApi.FunctionTool> result = SpringAiToolMapper.toOpenAiTools(List.of(tool));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo(OpenAiApi.FunctionTool.Type.FUNCTION);
        assertThat(result.get(0).getFunction().getName()).isEqualTo("read_file");
        assertThat(result.get(0).getFunction().getDescription()).isEqualTo("Reads a file");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) result.get(0).getFunction().getParameters();
        assertThat(params).containsEntry("type", "object");
    }

    @Test
    void returnsEmptyListForNullTools() {
        assertThat(SpringAiToolMapper.toOpenAiTools(null)).isEmpty();
    }

    @Test
    void returnsEmptyListForEmptyTools() {
        assertThat(SpringAiToolMapper.toOpenAiTools(List.of())).isEmpty();
    }

    @Test
    void fallsBackToObjectSchemaForInvalidJson() {
        ToolDefinition tool = new ToolDefinition("bad", "desc", "not-json");

        List<OpenAiApi.FunctionTool> result = SpringAiToolMapper.toOpenAiTools(List.of(tool));

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) result.get(0).getFunction().getParameters();
        assertThat(params).containsEntry("type", "object");
    }
}
