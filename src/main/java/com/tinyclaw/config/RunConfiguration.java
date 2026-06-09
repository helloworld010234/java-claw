package com.tinyclaw.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.application.run.ScriptedRunExecutor;
import com.tinyclaw.application.tool.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembly configuration for run-related application beans.
 */
@Configuration
public class RunConfiguration {

    @Bean
    ScriptedRunExecutor scriptedRunExecutor(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        return new ScriptedRunExecutor(toolRegistry, objectMapper);
    }
}
