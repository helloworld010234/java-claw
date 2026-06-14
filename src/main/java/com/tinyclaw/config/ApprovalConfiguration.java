package com.tinyclaw.config;

import com.tinyclaw.application.approval.ApprovalResumeLockRegistry;
import com.tinyclaw.application.approval.ApprovalResumeService;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import com.tinyclaw.ports.persistence.MessageRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import com.tinyclaw.ports.session.SessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Assembly configuration for approval-related application beans.
 */
@Configuration
public class ApprovalConfiguration {

    @Bean
    ApprovalResumeLockRegistry approvalResumeLockRegistry() {
        return new ApprovalResumeLockRegistry();
    }

    @Bean
    ApprovalResumeService approvalResumeService(ApprovalRepositoryPort approvalRepository,
                                                RunRepositoryPort runRepository,
                                                ToolExecutionRepositoryPort toolExecutionRepository,
                                                ToolRegistry toolRegistry,
                                                ApprovalResumeLockRegistry lockRegistry,
                                                Clock clock,
                                                SessionService sessionService,
                                                MessageRepositoryPort messageRepository,
                                                AgentEngine agentEngine,
                                                AgentProperties agentProperties) {
        return new ApprovalResumeService(
            approvalRepository,
            runRepository,
            toolExecutionRepository,
            toolRegistry,
            lockRegistry,
            clock,
            sessionService,
            messageRepository,
            agentEngine,
            agentProperties.getMaxTurns()
        );
    }
}
