package com.tinyclaw.domain.message;

import com.tinyclaw.domain.common.TinyClawDomainException;

import java.util.Objects;

/**
 * 消息角色枚举，对应 LLM 协议中的标准角色。
 */
public enum Role {
    SYSTEM,
    USER,
    ASSISTANT;

    /**
     * 从 wire value（小写字符串）解析 Role。
     *
     * @param value wire value，如 "system"、"user"、"assistant"
     * @return 对应的 Role
     * @throws TinyClawDomainException 如果 value 为 null 或无法识别
     */
    public static Role fromWireValue(String value) {
        if (value == null) {
            throw new TinyClawDomainException("Role wire value must not be null");
        }
        return switch (value.toLowerCase()) {
            case "system" -> SYSTEM;
            case "user" -> USER;
            case "assistant" -> ASSISTANT;
            default -> throw new TinyClawDomainException(
                    "Unknown Role wire value: '" + value + "'. Expected: system, user, assistant");
        };
    }

    /**
     * 返回 wire value（小写字符串）。
     */
    public String wireValue() {
        return name().toLowerCase();
    }
}
