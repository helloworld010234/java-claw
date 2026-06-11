package com.tinyclaw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.adapters.observability.AgentMetrics;
import com.tinyclaw.adapters.persistence.JdbcUsageRepository;
import com.tinyclaw.adapters.reporter.CompositeReporter;
import com.tinyclaw.adapters.reporter.ConsoleReporter;
import com.tinyclaw.adapters.reporter.UsagePersistingReporter;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.application.run.ScriptedRunExecutor;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.ports.persistence.MessageRepositoryPort;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import com.tinyclaw.ports.persistence.UsageRepositoryPort;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.session.SessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

@Configuration
public class RunConfiguration {

    @Bean
    ScriptedRunExecutor scriptedRunExecutor(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        return new ScriptedRunExecutor(toolRegistry, objectMapper);
    }

    @Bean
    UsageRepositoryPort usageRepositoryPort(JdbcTemplate jdbcTemplate) {
        return new JdbcUsageRepository(jdbcTemplate);
    }

    @Bean
    @Primary
    Reporter reporter(UsageRepositoryPort usageRepositoryPort, TinyClawModelProperties modelProperties) {
        Reporter console = new ConsoleReporter();
        // Usage persistence is now handled by AgentRunExecutionService as a critical lifecycle action.
        // UsagePersistingReporter is kept for backward compatibility but is no longer in the critical path.
        return new CompositeReporter(List.of(console));
    }

    @Bean(destroyMethod = "shutdown")
    java.util.concurrent.ExecutorService agentRunExecutor() {
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    AgentRunExecutionService agentRunExecutionService(
            RunRepositoryPort runRepository,
            MessageRepositoryPort messageRepository,
            SessionService sessionService,
            ObjectMapper objectMapper,
            Reporter reporter,
            UsageRepositoryPort usageRepositoryPort,
            com.tinyclaw.ports.observability.AgentMetricsPort agentMetrics) {
        return new AgentRunExecutionService(
            runRepository, messageRepository, sessionService, objectMapper, reporter, usageRepositoryPort, agentMetrics
        );
    }
}
