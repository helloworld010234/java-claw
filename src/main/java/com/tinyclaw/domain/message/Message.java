package com.tinyclaw.domain.message;

import com.tinyclaw.domain.common.DomainGuards;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Agent 对话中的单条消息。
 *
 * <p>表示模型协议层消息，不包含数据库 id、sessionId、createdAt 等持久化字段。</p>
 */
public final class Message {

    private final Role role;
    private final String content;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;
    private final Usage usage;

    private Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId, Usage usage) {
        this.role = DomainGuards.requireNonNull(role, "role");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        this.toolCallId = toolCallId;
        this.usage = usage;
    }

    public Role role() {
        return role;
    }

    public String content() {
        return content;
    }

    /**
     * 返回不可变的 ToolCall 列表，永远不会为 null。
     */
    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public Usage usage() {
        return usage;
    }

    /**
     * 创建 system 角色消息。
     */
    public static Message system(String content) {
        return new Message(Role.SYSTEM, content != null ? content : "", List.of(), null, null);
    }

    /**
     * 创建 user 角色消息。
     */
    public static Message user(String content) {
        return new Message(Role.USER, content != null ? content : "", List.of(), null, null);
    }

    /**
     * 创建 assistant 角色消息（纯文本，无 tool call）。
     */
    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content != null ? content : "", List.of(), null, null);
    }

    /**
     * 创建 assistant 角色消息（携带 tool calls）。
     *
     * @param toolCalls 不能为空列表
     */
    public static Message assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("toolCalls must not be null or empty for assistantWithToolCalls");
        }
        return new Message(Role.ASSISTANT, content != null ? content : "", toolCalls, null, null);
    }

    /**
     * 创建 tool observation 消息（作为 user 角色的 tool result 回传）。
     *
     * @param toolCallId 必须非空
     * @param output     工具输出
     */
    public static Message toolObservation(String toolCallId, String output) {
        DomainGuards.requireNonBlank(toolCallId, "toolCallId");
        return new Message(Role.USER, output != null ? output : "", List.of(), toolCallId, null);
    }
}
