package com.tinyclaw.config;

import com.tinyclaw.application.tool.AllowAllPolicy;
import com.tinyclaw.application.tool.DangerousCommandPolicy;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.ports.tool.AgentTool;
import com.tinyclaw.ports.tool.ToolExecutionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Tool assembly configuration: wires all discovered {@link AgentTool} beans
 * into the application-level {@link ToolRegistry}.
 */
@Configuration
public class ToolConfiguration {

    @Bean
    ToolRegistry toolRegistry(List<AgentTool> tools, List<ToolExecutionPolicy> policies) {
        return new ToolRegistry(tools, policies);
    }

    @Bean
    ToolExecutionPolicy allowAllPolicy() {
        return new AllowAllPolicy();
    }

    @Bean
    ToolExecutionPolicy dangerousCommandPolicy() {
        return new DangerousCommandPolicy();
    }
}
