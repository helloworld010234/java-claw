package com.tinyclaw.application.chatops;

import com.tinyclaw.domain.approval.ApprovalRequest;
import com.tinyclaw.domain.approval.ApprovalStatus;
import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.ports.chatops.ChatOpsEvent;
import com.tinyclaw.ports.chatops.ChatOpsMessageSender;
import com.tinyclaw.ports.chatops.ChatOpsOutboundMessage;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
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
 * <p>Only {@link ApprovalStatus#PENDING} requests can be acted upon. The result
 * is reported back to the same chat channel via {@link ChatOpsMessageSender}.</p>
 */
public class ChatOpsApprovalCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatOpsApprovalCommandHandler.class);

    private final ApprovalRepositoryPort approvalRepository;
    private final Clock clock;

    public ChatOpsApprovalCommandHandler(ApprovalRepositoryPort approvalRepository,
                                         Clock clock) {
        this.approvalRepository = DomainGuards.requireNonNull(approvalRepository, "approvalRepository");
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
        ApprovalRequest updated;
        String action;
        if (isApprove) {
            updated = approval.approve("approved via ChatOps", now);
            action = "approved";
        } else {
            updated = approval.reject("rejected via ChatOps", now);
            action = "rejected";
        }
        approvalRepository.update(updated);

        log.info("[ChatOps] Approval {} {} by chat {}", approvalId, action, event.chatId());
        sender.sendMessage(event.chatId(),
            ChatOpsOutboundMessage.runCompleted("approval",
                "Approval " + approvalId + " has been " + action));
        return true;
    }
}
