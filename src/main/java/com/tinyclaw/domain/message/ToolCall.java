package com.tinyclaw.domain.message;

import com.tinyclaw.domain.common.DomainGuards;

/**
 * LLM 请求执行某个工具的调用描述。
 *
 * @param id            工具调用的唯一标识
 * @param name          工具名称（snake_case）
 * @param argumentsJson 工具参数的 JSON 字符串
 */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        DomainGuards.requireNonBlank(id, "id");
        DomainGuards.requireNonBlank(name, "name");
        DomainGuards.requireNonBlank(argumentsJson, "argumentsJson");
    }

    /**
     * 工厂方法创建 ToolCall。
     */
    public static ToolCall of(String id, String name, String argumentsJson) {
        return new ToolCall(id, name, argumentsJson);
    }
}
