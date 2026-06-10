package com.tinyclaw.domain.approval;

/**
 * 审批请求状态。
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    RESUMING,
    RESUMED
}
