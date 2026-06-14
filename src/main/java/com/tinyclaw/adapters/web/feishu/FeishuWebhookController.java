package com.tinyclaw.adapters.web.feishu;

import com.tinyclaw.adapters.web.feishu.dto.FeishuEventParser;
import com.tinyclaw.adapters.web.feishu.dto.FeishuWebhookPayload;
import com.tinyclaw.application.chatops.ChatOpsEventHandler;
import com.tinyclaw.config.ChatOpsProperties;
import com.tinyclaw.ports.chatops.ChatOpsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Feishu webhook controller.
 *
 * <p>Adapter-layer responsibility: protocol parsing, challenge response, and
 * delegation to the application-layer {@link ChatOpsEventHandler}.</p>
 *
 * <p>Security note: signature verification is a P1 follow-up item.
 * When {@code chatops.enabled=false} the endpoint returns 503 and does not process events.</p>
 */
@RestController
@RequestMapping("/webhook/feishu")
@ConditionalOnProperty(prefix = "tiny-claw.chatops", name = "enabled", havingValue = "true")
public class FeishuWebhookController {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebhookController.class);

    private final ChatOpsEventHandler eventHandler;
    private final FeishuEventParser eventParser;
    private final ChatOpsProperties chatOpsProperties;

    public FeishuWebhookController(ChatOpsEventHandler eventHandler,
                                   FeishuEventParser eventParser,
                                   ChatOpsProperties chatOpsProperties) {
        this.eventHandler = eventHandler;
        this.eventParser = eventParser;
        this.chatOpsProperties = chatOpsProperties;
    }

    @PostMapping("/event")
    public ResponseEntity<Map<String, String>> receiveEvent(@RequestBody FeishuWebhookPayload payload) {
        if (!chatOpsProperties.isEnabled()) {
            log.warn("[FeishuWebhook] ChatOps is disabled; rejecting event");
            return ResponseEntity.status(503)
                .body(Map.of("status", "ChatOps disabled"));
        }

        // Security: verify token must be configured and match
        String verifyToken = chatOpsProperties.getVerifyToken();
        if (verifyToken == null || verifyToken.isBlank()) {
            log.warn("[FeishuWebhook] verifyToken is not configured; rejecting event");
            return ResponseEntity.status(401)
                .body(Map.of("error", "Unauthorized"));
        }

        String payloadToken = payload != null ? payload.token() : null;
        if (payloadToken == null || !payloadToken.equals(verifyToken)) {
            log.warn("[FeishuWebhook] Token mismatch; rejecting event");
            return ResponseEntity.status(401)
                .body(Map.of("error", "Unauthorized"));
        }

        ChatOpsEvent event = eventParser.parse(payload);

        if (event.isUrlVerification()) {
            String challenge = payload.challengeToken();
            if (challenge == null || challenge.isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing challenge"));
            }
            log.info("[FeishuWebhook] URL verification challenge accepted");
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }

        if (event.type() == ChatOpsEvent.Type.UNKNOWN) {
            log.info("[FeishuWebhook] Ignoring unsupported event: type={}, chatId={}",
                event.type(), event.chatId());
            return ResponseEntity.ok(Map.of("status", "ignored"));
        }

        if (!event.isRunnableTextMessage()) {
            log.info("[FeishuWebhook] Ignoring non-runnable event: chatId={}, text blank={}",
                event.chatId(), event.text() == null || event.text().isBlank());
            return ResponseEntity.ok(Map.of("status", "ignored"));
        }

        // Security: chat allowlist
        List<String> allowedChatIds = chatOpsProperties.getAllowedChatIds();
        if (allowedChatIds != null && !allowedChatIds.isEmpty()) {
            if (event.chatId() == null || !allowedChatIds.contains(event.chatId())) {
                log.warn("[FeishuWebhook] ChatId {} not in allowlist; rejecting event", event.chatId());
                return ResponseEntity.status(403)
                    .body(Map.of("error", "Chat not allowed"));
            }
        }

        boolean accepted = eventHandler.handle(event);
        if (accepted) {
            return ResponseEntity.ok(Map.of("status", "accepted"));
        } else {
            return ResponseEntity.ok(Map.of("status", "duplicate or rejected"));
        }
    }
}
