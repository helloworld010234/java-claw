package com.tinyclaw.adapters.cli;

import com.tinyclaw.application.benchmark.BenchmarkResult;
import com.tinyclaw.application.benchmark.BenchmarkRunner;
import com.tinyclaw.application.benchmark.BenchmarkSuite;
import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.run.AgentRunExecutionService;
import com.tinyclaw.config.AgentProperties;
import com.tinyclaw.config.TinyClawModelProperties;
import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code bench --engine real} uses the Spring-configured real
 * {@link LlmGateway} when the model is enabled and configured.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tiny-claw.model.enabled=true",
    "tiny-claw.model.name=real-benchmark-model",
    "tiny-claw.model.api-key=sk-real-benchmark",
    "spring.main.allow-bean-definition-overriding=true"
})
class BenchmarkCommandRealModeTest {

    @Autowired
    private AgentRunExecutionService runExecutionService;

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private AgentProperties agentProperties;

    @Autowired
    private TinyClawModelProperties modelProperties;

    @Autowired
    private LlmGateway realLlmGateway;

    @Test
    void realGatewayBeanIsPresent() {
        assertThat(realLlmGateway).isNotNull();
        assertThat(modelProperties.isEnabled()).isTrue();
        assertThat(modelProperties.getApiKey()).isEqualTo("sk-real-benchmark");
    }

    @Test
    void benchmarkRunnerUsesRealGatewayWhenEngineTypeIsReal() {
        AtomicBoolean called = new AtomicBoolean(false);
        LlmGateway trackingGateway = request -> {
            called.set(true);
            return new LlmResponse("Real response", List.of(), null);
        };

        BenchmarkRunner runner = new BenchmarkRunner(
            runExecutionService, agentEngine, agentProperties.getMaxTurns()
        );

        BenchmarkResult result = runner.run(
            BenchmarkSuite.failingCase(),
            Path.of("target", "benchmark-real-test-" + System.currentTimeMillis()),
            trackingGateway,
            "benchmark-real"
        );

        assertThat(called).isTrue();
        assertThat(result.errorReason()).contains("Intentional validation failure");
    }

    @TestConfiguration
    static class RealGatewayStubConfiguration {

        @Bean
        @Primary
        LlmGateway realLlmGateway() {
            return request -> new LlmResponse("Stub real benchmark response", List.of(), null);
        }
    }
}
