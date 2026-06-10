package com.tinyclaw.config;

import com.tinyclaw.application.approval.ApprovalResumeService;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.ToolExecutionRepositoryPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Assembly configuration for approval-related application beans.
 */
@Configuration
public class ApprovalConfiguration {

    @Bean
    ApprovalResumeService approvalResumeService(ApprovalRepositoryPort approvalRepository,
                                                RunRepositoryPort runRepository,
                                                ToolExecutionRepositoryPort toolExecutionRepository,
                                                ToolRegistry toolRegistry,
                                                Clock clock) {
        return new ApprovalResumeService(
            approvalRepository,
            runRepository,
            toolExecutionRepository,
            toolRegistry,
            clock
        );
    }
}
