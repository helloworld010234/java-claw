package com.tinyclaw.adapters.web;

import com.tinyclaw.adapters.web.dto.ApprovalActionRequest;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 审批操作控制器。
 *
 * <p>POST /api/v1/approvals/{id}/action 对指定审批请求执行批准或拒绝。</p>
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalRepositoryPort approvalRepository;

    public ApprovalController(ApprovalRepositoryPort approvalRepository) {
        this.approvalRepository = approvalRepository;
    }

    /**
     * 对审批请求执行操作。
     *
     * @param id      审批 ID
     * @param request 操作请求（approve 或 reject）
     * @return 操作结果
     */
    @PostMapping("/{id}/action")
    public ResponseEntity<Map<String, Object>> handleApprovalAction(
            @PathVariable String id,
            @Valid @RequestBody ApprovalActionRequest request) {

        Optional<ApprovalRequest> maybeApproval = approvalRepository.findById(id);
        if (maybeApproval.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ApprovalRequest approval = maybeApproval.get();
        String reason = request.reason() != null && !request.reason().isBlank()
            ? request.reason()
            : "action via API";
        Instant now = java.time.Instant.now();

        if ("approve".equalsIgnoreCase(request.action())) {
            if (approval.status() != ApprovalStatus.PENDING) {
                return ResponseEntity.status(409)
                    .body(Map.of("error", "Approval is not in PENDING status: " + approval.status()));
            }
            ApprovalRequest approved = approval.approve(reason, now);
            approvalRepository.update(approved);
            return ResponseEntity.ok(Map.of(
                "approvalId", id,
                "action", "approve",
                "status", "APPROVED"
            ));
        } else if ("reject".equalsIgnoreCase(request.action())) {
            if (approval.status() != ApprovalStatus.PENDING) {
                return ResponseEntity.status(409)
                    .body(Map.of("error", "Approval is not in PENDING status: " + approval.status()));
            }
            ApprovalRequest rejected = approval.reject(reason, now);
            approvalRepository.update(rejected);
            return ResponseEntity.ok(Map.of(
                "approvalId", id,
                "action", "reject",
                "status", "REJECTED"
            ));
        }

        return ResponseEntity.badRequest()
            .body(Map.of("error", "Invalid action: " + request.action()));
    }
}
