package com.tinyclaw.config;

import com.tinyclaw.application.approval.ApprovalGatePolicy;
import com.tinyclaw.application.tool.AllowAllPolicy;
import com.tinyclaw.application.tool.DangerousCommandPolicy;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.ports.persistence.ApprovalRepositoryPort;
import com.tinyclaw.ports.tool.AgentTool;
import com.tinyclaw.ports.tool.ToolExecutionPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Tool assembly configuration: wires all discovered {@link AgentTool} beans
 * into the application-level {@link ToolRegistry}.
 */
@Configuration
@EnableConfigurationProperties(ApprovalProperties.class)
public class ToolConfiguration {

    @Bean
    ToolRegistry toolRegistry(List<AgentTool> tools, List<ToolExecutionPolicy> policies) {
        return new ToolRegistry(tools, policies);
    }

    @Bean
    ToolExecutionPolicy dangerousCommandPolicy() {
        return new DangerousCommandPolicy();
    }

    @Bean
    ToolExecutionPolicy approvalGatePolicy(ApprovalRepositoryPort approvalRepository,
                                           ApprovalProperties approvalProperties,
                                           Clock clock) {
        return new ApprovalGatePolicy(approvalRepository, approvalProperties.getRequiredTools(), clock);
    }

    @Bean
    ToolExecutionPolicy allowAllPolicy() {
        return new AllowAllPolicy();
    }
}
