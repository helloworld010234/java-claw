package com.tinyclaw.domain.message;

import com.tinyclaw.domain.common.DomainGuards;

/**
 * 工具定义，描述一个可供 LLM 调用的工具。
 *
 * @param name           工具名称（snake_case）
 * @param description    工具功能描述
 * @param inputSchemaJson 工具输入参数的 JSON Schema 字符串
 */
public record ToolDefinition(String name, String description, String inputSchemaJson) {

    public ToolDefinition {
        DomainGuards.requireNonBlank(name, "name");
        DomainGuards.requireNonBlank(description, "description");
        DomainGuards.requireNonBlank(inputSchemaJson, "inputSchemaJson");
    }
}
