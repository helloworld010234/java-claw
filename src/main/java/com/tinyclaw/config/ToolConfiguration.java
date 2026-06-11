package com.tinyclaw.config;

import com.tinyclaw.adapters.observability.AgentMetrics;
import com.tinyclaw.adapters.tools.filesystem.ReadFileTool;
import com.tinyclaw.application.approval.ApprovalGatePolicy;
import com.tinyclaw.application.tool.AllowAllPolicy;
import com.tinyclaw.application.tool.DangerousCommandPolicy;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.ports.observability.AgentMetricsPort;
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
@EnableConfigurationProperties({ApprovalProperties.class, WorkspaceProperties.class})
public class ToolConfiguration {

    @Bean
    ToolRegistry toolRegistry(List<AgentTool> tools, List<ToolExecutionPolicy> policies,
                              AgentMetrics agentMetrics) {
        return new ToolRegistry(tools, policies, agentMetrics);
    }

    /**
     * 只读工具注册表，供子 Agent 使用。
     * 子 Agent 只能读取文件系统，不能执行写入或 shell 命令。
     *
     * <p>TODO: 后续应添加 GrepTool 和 GlobTool 以增强子 Agent 的搜索能力。</p>
     */
    @Bean
    ToolRegistry readOnlyToolRegistry(ReadFileTool readFileTool, AgentMetricsPort agentMetrics) {
        List<AgentTool> readOnlyTools = new ArrayList<>();
        readOnlyTools.add(readFileTool);
        // 子 Agent 不需要审批策略，使用 AllowAllPolicy
        List<ToolExecutionPolicy> readOnlyPolicies = new ArrayList<>();
        readOnlyPolicies.add(new AllowAllPolicy());
        return new ToolRegistry(readOnlyTools, readOnlyPolicies, agentMetrics);
    }

    @Bean
    ToolExecutionPolicy dangerousCommandPolicy() {
        return new DangerousCommandPolicy();
    }

    @Bean
    ToolExecutionPolicy approvalGatePolicy(ApprovalRepositoryPort approvalRepository,
                                           ApprovalProperties approvalProperties,
                                           Clock clock) {
        return new ApprovalGatePolicy(approvalRepository, approvalProperties.getRequiredTools(), clock, approvalProperties.isEnabled());
    }
}
