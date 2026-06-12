package com.tinyclaw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.web.feishu.FeishuChatOpsMessageSender;
import com.tinyclaw.adapters.web.feishu.FeishuWebhookController;
import com.tinyclaw.adapters.web.feishu.dto.FeishuEventParser;
import com.tinyclaw.application.chatops.ChatOpsApprovalCommandHandler;
import com.tinyclaw.application.chatops.ChatOpsEventHandler;
import com.tinyclaw.application.chatops.ChatOpsReporter;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.ports.chatops.ChatOpsMessageSender;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;

/**
 * Spring configuration for ChatOps components.
 *
 * <p>All ChatOps beans are conditional on {@code tiny-claw.chatops.enabled=true}.
 * When disabled, no Feishu webhook endpoint or sender is registered.</p>
 */
@Configuration
@EnableConfigurationProperties(ChatOpsProperties.class)
public class ChatOpsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChatOpsConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "tiny-claw.chatops", name = "enabled", havingValue = "true")
    ChatOpsMessageSender chatOpsMessageSender(ChatOpsProperties properties) {
        return new FeishuChatOpsMessageSender(properties.isEnabled());
    }

    @Bean
    @ConditionalOnProperty(prefix = "tiny-claw.chatops", name = "enabled", havingValue = "true")
    FeishuEventParser feishuEventParser(ObjectMapper objectMapper) {
        return new FeishuEventParser(objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "tiny-claw.chatops", name = "enabled", havingValue = "true")
    ChatOpsEventHandler chatOpsEventHandler(
            AgentRunExecutionService executionService,
            SessionService sessionService,
            ChatOpsMessageSender messageSender,
            ExecutorService agentRunExecutor,
            ChatOpsProperties properties,
            AgentProperties agentProperties,
            AgentEngine agentEngine,
            ApprovalRepositoryPort approvalRepository) {
        Path workspace = Paths.get(properties.getWorkspace()).toAbsolutePath().normalize();
        ChatOpsApprovalCommandHandler approvalCommandHandler =
            new ChatOpsApprovalCommandHandler(approvalRepository, null);
        return new ChatOpsEventHandler(
            executionService,
            sessionService,
            messageSender,
            agentRunExecutor,
            workspace,
            agentProperties.getMaxTurns(),
            agentEngine,
            approvalCommandHandler
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "tiny-claw.chatops", name = "enabled", havingValue = "true")
    FeishuWebhookController feishuWebhookController(
            ChatOpsEventHandler eventHandler,
            FeishuEventParser eventParser,
            ChatOpsProperties properties) {
        return new FeishuWebhookController(eventHandler, eventParser, properties);
    }

    /**
     * ChatOps reporter bean that can be added to the composite reporter when ChatOps is enabled.
     * This is a per-request reporter; the event handler creates one per run.
     */
    public static ChatOpsReporter createChatOpsReporter(ChatOpsMessageSender sender, String chatId) {
        return new ChatOpsReporter(sender, chatId);
    }
}
