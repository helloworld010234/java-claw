package com.tinyclaw.domain.message;

import com.tinyclaw.domain.common.DomainGuards;

import java.util.Objects;

/**
 * 工具执行结果。
 *
 * @param toolCallId 关联的 ToolCall id
 * @param output     工具输出内容，允许空字符串，不允许 null
 * @param error      true 表示执行失败
 */
public record ToolResult(String toolCallId, String output, boolean error) {

    public ToolResult {
        DomainGuards.requireNonBlank(toolCallId, "toolCallId");
        Objects.requireNonNull(output, "output must not be null");
    }

    /**
     * 创建成功的工具结果。
     */
    public static ToolResult success(String toolCallId, String output) {
        return new ToolResult(toolCallId, output != null ? output : "", false);
    }

    /**
     * 创建失败的工具结果。
     */
    public static ToolResult failure(String toolCallId, String output) {
        return new ToolResult(toolCallId, output != null ? output : "", true);
    }
}
