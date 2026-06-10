package com.tinyclaw.config;

import com.tinyclaw.adapters.llm.fake.FakeLlmGateway;
import com.tinyclaw.adapters.reporter.ConsoleReporter;
import com.tinyclaw.adapters.session.InMemorySessionService;
import com.tinyclaw.application.engine.AgentContextBuilder;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.ContextCompactor;
import com.tinyclaw.application.engine.PromptComposer;
import com.tinyclaw.application.engine.ToolFailureRecoveryAdvisor;
import com.tinyclaw.application.engine.WorkingMemorySelector;
import com.tinyclaw.application.tool.ToolRegistry;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmException;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.session.SessionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Spring configuration for agent engine components.
 *
 * <p>Provides in-memory defaults for session storage and reporting.
 * A real {@link LlmGateway} bean must be provided by a profile-specific
 * configuration (e.g., Spring AI adapter) or a test {@code @TestConfiguration}.</p>
 */
@Configuration
@EnableConfigurationProperties(TinyClawModelProperties.class)
public class EngineConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PromptComposer promptComposer() {
        return new PromptComposer();
    }

    @Bean
    WorkingMemorySelector workingMemorySelector() {
        return new WorkingMemorySelector();
    }

    @Bean
    ContextCompactor contextCompactor() {
        return new ContextCompactor();
    }

    @Bean
    AgentContextBuilder agentContextBuilder(PromptComposer promptComposer,
                                            WorkingMemorySelector workingMemorySelector,
                                            ContextCompactor contextCompactor) {
        return new AgentContextBuilder(promptComposer, workingMemorySelector, contextCompactor);
    }

    @Bean
    ToolFailureRecoveryAdvisor toolFailureRecoveryAdvisor() {
        return new ToolFailureRecoveryAdvisor();
    }

    @Bean
    @ConditionalOnMissingBean(SessionService.class)
    SessionService sessionService() {
        return new InMemorySessionService();
    }

    @Bean
    @ConditionalOnMissingBean(Reporter.class)
    Reporter reporter() {
        return new ConsoleReporter();
    }

    @Bean
    @ConditionalOnMissingBean(LlmGateway.class)
    LlmGateway defaultLlmGateway() {
        return request -> {
            throw new LlmException(
                "No LlmGateway bean configured. "
                + "Provide a production adapter (e.g., Spring AI) or a test fake."
            );
        };
    }

    @Bean
    AgentEngine agentEngine(LlmGateway llmGateway,
                            ToolRegistry toolRegistry,
                            PromptComposer promptComposer,
                            Reporter reporter,
                            SessionService sessionService,
                            Clock clock,
                            AgentContextBuilder agentContextBuilder,
                            ToolFailureRecoveryAdvisor toolFailureRecoveryAdvisor) {
        return new AgentEngine(llmGateway, toolRegistry, promptComposer, reporter, sessionService, clock,
            agentContextBuilder, toolFailureRecoveryAdvisor);
    }
}
