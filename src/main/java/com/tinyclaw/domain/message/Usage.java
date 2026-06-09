package com.tinyclaw.domain.message;

import com.tinyclaw.domain.common.DomainGuards;

/**
 * Token 使用量值对象。
 *
 * @param promptTokens     prompt 消耗的 token 数
 * @param completionTokens completion 消耗的 token 数
 */
public record Usage(int promptTokens, int completionTokens) {

    public Usage {
        DomainGuards.requireNonNegative(promptTokens, "promptTokens");
        DomainGuards.requireNonNegative(completionTokens, "completionTokens");
    }

    /**
     * 返回总 token 消耗量。
     */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
