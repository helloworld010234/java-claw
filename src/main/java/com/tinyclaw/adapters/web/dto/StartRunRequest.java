package com.tinyclaw.adapters.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * POST /api/v1/runs 请求体。
 *
 * <p>启动一个新的 Agent Run。</p>
 */
public record StartRunRequest(

    /**
     * 会话 ID。为空时自动创建新会话。
     */
    String sessionId,

    /**
     * 用户任务描述。必填。
     */
    @NotBlank(message = "prompt must not be blank")
    String prompt,

    /**
     * 工作区目录路径。为空时使用当前目录。
     */
    String workDir,

    /**
     * 最大轮数。为空时默认 20。
     */
    @Positive(message = "maxTurns must be positive")
    Integer maxTurns
) {
}
