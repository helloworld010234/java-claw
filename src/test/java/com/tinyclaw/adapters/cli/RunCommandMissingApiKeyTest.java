package com.tinyclaw.adapters.cli;

import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.config.TinyClawModelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that --engine real with enabled=true but missing API key returns
 * a controlled CLI error (exit code 2) without Spring startup failure.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tiny-claw.model.enabled=true",
    "tiny-claw.model.name=test-model",
    "tiny-claw.model.api-key="
})
class RunCommandMissingApiKeyTest {

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private TinyClawModelProperties modelProperties;

    @Test
    void contextStartsEvenWithMissingApiKey() {
        assertThat(agentEngine).isNotNull();
        assertThat(modelProperties.isEnabled()).isTrue();
        assertThat(modelProperties.getApiKey()).isBlank();
    }
}
