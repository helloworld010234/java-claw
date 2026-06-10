package com.tinyclaw.config;

import com.tinyclaw.ports.llm.LlmGateway;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TinyClawModelConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withPropertyValues("tiny-claw.model.enabled=true")
        .withConfiguration(AutoConfigurations.of(TinyClawModelConfiguration.class))
        .withUserConfiguration(MeterRegistryConfig.class)
        .withBean(ChatModel.class, () -> mock(ChatModel.class))
        .withBean(TinyClawModelProperties.class, () -> {
            TinyClawModelProperties p = new TinyClawModelProperties();
            p.setEnabled(true);
            p.setApiKey("sk-test");
            p.setName("test-model");
            return p;
        });

    @Test
    void createsLlmGatewayWhenEnabled() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LlmGateway.class);
        });
    }

    @Test
    void doesNotCreateLlmGatewayWhenDisabled() {
        new ApplicationContextRunner()
            .withPropertyValues("tiny-claw.model.enabled=false")
            .withConfiguration(AutoConfigurations.of(TinyClawModelConfiguration.class))
            .withUserConfiguration(MeterRegistryConfig.class)
            .withBean(ChatModel.class, () -> mock(ChatModel.class))
            .withBean(TinyClawModelProperties.class, () -> {
                TinyClawModelProperties p = new TinyClawModelProperties();
                p.setEnabled(false);
                return p;
            })
            .run(context -> {
                assertThat(context).doesNotHaveBean("realLlmGateway");
            });
    }

    @Configuration
    static class MeterRegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
