package com.tinyclaw.adapters.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * POST /api/v1/approvals/{id}/action 请求体。
 *
 * <p>对审批请求执行批准或拒绝操作。</p>
 */
public record ApprovalActionRequest(

    /**
     * 操作类型："approve" 或 "reject"。
     */
    @NotBlank(message = "action must not be blank")
    @Pattern(regexp = "approve|reject", message = "action must be 'approve' or 'reject'")
    String action,

    /**
     * 审批理由。为空时使用默认理由。
     */
    String reason
) {
}
