package com.tinyclaw.application.chatops;

import com.tinyclaw.application.approval.ApprovalResumeResult;
import com.tinyclaw.application.approval.ApprovalResumeService;
import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.ports.chatops.ChatOpsEvent;
import com.tinyclaw.ports.chatops.ChatOpsMessageSender;
import com.tinyclaw.ports.chatops.ChatOpsOutboundMessage;
import com.tinyclaw.ports.chatops.ChatOpsSanitizer;
import com.tinyclaw.ports.persistence.AgentRunSummary;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Handles approve/reject text commands issued through a ChatOps channel.
 *
 * <p>Recognizes messages of the form:</p>
 * <ul>
 *   <li>{@code approve <approvalId>}</li>
 *   <li>{@code reject <approvalId>}</li>
 * </ul>
 *
 * <p>Only {@link ApprovalStatus#PENDING} requests can be acted upon. Approving
 * a request triggers {@link ApprovalResumeService} to resume the paused run.
 * Rejecting a request marks both the approval and the associated run as failed.
 * The result is reported back to the same chat channel via
 * {@link ChatOpsMessageSender}.</p>
 */
public class ChatOpsApprovalCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatOpsApprovalCommandHandler.class);

    private final ApprovalRepositoryPort approvalRepository;
    private final ApprovalResumeService approvalResumeService;
    private final RunRepositoryPort runRepository;
    private final Clock clock;

    public ChatOpsApprovalCommandHandler(ApprovalRepositoryPort approvalRepository,
                                         Clock clock) {
        this(approvalRepository, null, null, clock);
    }

    public ChatOpsApprovalCommandHandler(ApprovalRepositoryPort approvalRepository,
                                         ApprovalResumeService approvalResumeService,
                                         RunRepositoryPort runRepository,
                                         Clock clock) {
        this.approvalRepository = DomainGuards.requireNonNull(approvalRepository, "approvalRepository");
        this.approvalResumeService = approvalResumeService;
        this.runRepository = runRepository;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * Attempts to handle an approval command contained in the chat event.
     *
     * @param event   the incoming chat event
     * @param sender  the sender used to report the outcome
     * @return true if the event was an approval command and was processed
     */
    public boolean handle(ChatOpsEvent event, ChatOpsMessageSender sender) {
        if (event == null || event.text() == null || event.text().isBlank()) {
            return false;
        }

        String text = event.text().trim();
        String lower = text.toLowerCase();
        boolean isApprove = lower.equals("approve") || lower.startsWith("approve ");
        boolean isReject = lower.equals("reject") || lower.startsWith("reject ");
        if (!isApprove && !isReject) {
            return false;
        }

        String command = isApprove ? "approve" : "reject";
        String remainder = text.substring(command.length()).trim();
        if (remainder.isBlank()) {
            sender.sendMessage(event.chatId(),
                ChatOpsOutboundMessage.runFailed("approval", "Missing approval ID"));
            return true;
        }

        String approvalId = remainder;
        Optional<ApprovalRequest> maybeApproval = approvalRepository.findById(approvalId);
        if (maybeApproval.isEmpty()) {
            log.warn("[ChatOps] Approval command references unknown approval: {}", approvalId);
            sender.sendMessage(event.chatId(),
                ChatOpsOutboundMessage.runFailed("approval", "Approval not found: " + approvalId));
            return true;
        }

        ApprovalRequest approval = maybeApproval.get();
        if (approval.status() != ApprovalStatus.PENDING) {
            sender.sendMessage(event.chatId(),
                ChatOpsOutboundMessage.runFailed("approval",
                    "Approval " + approvalId + " is not pending (" + approval.status() + ")"));
            return true;
        }

        Instant now = clock.instant();
        if (isApprove) {
            ApprovalRequest approved = approval.approve("approved via ChatOps", now);
            approvalRepository.update(approved);
            log.info("[ChatOps] Approval {} approved by chat {}", approvalId, event.chatId());

            if (approvalResumeService != null) {
                ApprovalResumeResult result = approvalResumeService.resume(approvalId);
                sender.sendMessage(event.chatId(), buildResumeResultMessage(result));
            } else {
                sender.sendMessage(event.chatId(),
                    ChatOpsOutboundMessage.runCompleted("approval",
                        "Approval " + approvalId + " has been approved. Resume service not available."));
            }
        } else {
            ApprovalRequest rejected = approval.reject("rejected via ChatOps", now);
            approvalRepository.update(rejected);
            if (runRepository != null) {
                String safeReason = "Approval rejected: " + approvalId;
                int turnCount = runRepository.findById(approval.runId())
                    .map(AgentRunSummary::turnCount)
                    .orElse(0);
                runRepository.saveRunFailed(approval.runId(), turnCount, safeReason, now);
            }
            log.info("[ChatOps] Approval {} rejected by chat {}", approvalId, event.chatId());
            sender.sendMessage(event.chatId(),
                ChatOpsOutboundMessage.runFailed("approval",
                    "Approval " + approvalId + " has been rejected"));
        }

        return true;
    }

    private ChatOpsOutboundMessage buildResumeResultMessage(ApprovalResumeResult result) {
        if (!result.resumed()) {
            String safeReason = ChatOpsSanitizer.sanitize(result.output());
            return ChatOpsOutboundMessage.runFailed(result.runId(), safeReason);
        }
        if (result.runStatus().isTerminal() && result.runStatus().isFailure()) {
            String safeReason = ChatOpsSanitizer.sanitize(result.output());
            return ChatOpsOutboundMessage.runFailed(result.runId(), safeReason);
        }
        if (result.runStatus() == com.tinyclaw.domain.run.AgentRunStatus.WAITING_APPROVAL) {
            return ChatOpsOutboundMessage.runFailed(result.runId(),
                "Run paused for another approval after resuming " + result.approvalId());
        }
        String safeFinal = ChatOpsSanitizer.sanitize(result.finalMessage());
        return ChatOpsOutboundMessage.runCompleted(result.runId(), safeFinal);
    }
}
