package com.tinyclaw.config;

import com.tinyclaw.ports.llm.LlmGateway;
import com.tinyclaw.ports.persistence.UsageRepositoryPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.tinyclaw.application.tool.ToolRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void combinedWithEngineConfiguration_hasSingleLlmGatewayWhenEnabled() {
        new ApplicationContextRunner()
            .withPropertyValues(
                "tiny-claw.model.enabled=true",
                "tiny-claw.model.api-key=sk-test",
                "tiny-claw.model.name=test-model"
            )
            .withConfiguration(AutoConfigurations.of(EngineConfiguration.class, TinyClawModelConfiguration.class))
            .withUserConfiguration(MeterRegistryConfig.class)
            .withBean(ChatModel.class, () -> mock(ChatModel.class))
            .withBean(ToolRegistry.class, () -> new ToolRegistry(java.util.List.of()))
            .run(context -> {
                assertThat(context).hasSingleBean(LlmGateway.class);
                assertThat(context).hasBean("realLlmGateway");
                assertThat(context).doesNotHaveBean("defaultLlmGateway");
            });
    }

    @Test
    void combinedWithEngineConfiguration_usesDefaultLlmGatewayWhenDisabled() {
        new ApplicationContextRunner()
            .withPropertyValues("tiny-claw.model.enabled=false")
            .withConfiguration(AutoConfigurations.of(EngineConfiguration.class, TinyClawModelConfiguration.class))
            .withUserConfiguration(MeterRegistryConfig.class)
            .withBean(ChatModel.class, () -> mock(ChatModel.class))
            .withBean(ToolRegistry.class, () -> new ToolRegistry(java.util.List.of()))
            .run(context -> {
                assertThat(context).hasSingleBean(LlmGateway.class);
                assertThat(context).hasBean("defaultLlmGateway");
                assertThat(context).doesNotHaveBean("realLlmGateway");
            });
    }

    @Test
    void combinedWithEngineConfiguration_usesDefaultLlmGatewayWhenPropertyMissing() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EngineConfiguration.class, TinyClawModelConfiguration.class))
            .withUserConfiguration(MeterRegistryConfig.class)
            .withBean(ChatModel.class, () -> mock(ChatModel.class))
            .withBean(ToolRegistry.class, () -> new ToolRegistry(java.util.List.of()))
            .run(context -> {
                assertThat(context).hasSingleBean(LlmGateway.class);
                assertThat(context).hasBean("defaultLlmGateway");
                assertThat(context).doesNotHaveBean("realLlmGateway");
            });
    }

    @Test
    void startsSuccessfullyWhenEnabledButApiKeyMissing() {
        new ApplicationContextRunner()
            .withPropertyValues("tiny-claw.model.enabled=true")
            .withConfiguration(AutoConfigurations.of(TinyClawModelConfiguration.class))
            .withUserConfiguration(MeterRegistryConfig.class)
            .withBean(TinyClawModelProperties.class, () -> {
                TinyClawModelProperties p = new TinyClawModelProperties();
                p.setEnabled(true);
                p.setApiKey("");
                p.setName("test-model");
                return p;
            })
            .run(context -> {
                assertThat(context).hasBean("realLlmGateway");
            });
    }

    @Test
    void failFastGatewayWhenApiKeyMissing() {
        new ApplicationContextRunner()
            .withPropertyValues("tiny-claw.model.enabled=true")
            .withConfiguration(AutoConfigurations.of(TinyClawModelConfiguration.class))
            .withUserConfiguration(MeterRegistryConfig.class)
            .withBean(TinyClawModelProperties.class, () -> {
                TinyClawModelProperties p = new TinyClawModelProperties();
                p.setEnabled(true);
                p.setApiKey("");
                p.setName("test-model");
                return p;
            })
            .run(context -> {
                com.tinyclaw.ports.llm.LlmGateway gateway = context.getBean(com.tinyclaw.ports.llm.LlmGateway.class);
                assertThatThrownBy(() -> gateway.generate(
                    new com.tinyclaw.ports.llm.LlmRequest(
                        "test", java.util.List.of(), java.util.List.of(),
                        com.tinyclaw.ports.llm.LlmRequestOptions.defaults()
                    )
                ))
                    .isInstanceOf(com.tinyclaw.ports.llm.LlmException.class)
                    .hasMessageContaining("requires an API key");
            });
    }

    @Test
    void injectsUsageRepositoryWhenAvailable() {
        new ApplicationContextRunner()
            .withPropertyValues(
                "tiny-claw.model.enabled=true",
                "tiny-claw.model.api-key=sk-test",
                "tiny-claw.model.name=test-model"
            )
            .withConfiguration(AutoConfigurations.of(TinyClawModelConfiguration.class))
            .withUserConfiguration(MeterRegistryConfig.class)
            .withBean(ChatModel.class, () -> mock(ChatModel.class))
            .withBean(UsageRepositoryPort.class, () -> mock(UsageRepositoryPort.class))
            .withBean(TinyClawModelProperties.class, () -> {
                TinyClawModelProperties p = new TinyClawModelProperties();
                p.setEnabled(true);
                p.setApiKey("sk-test");
                p.setName("test-model");
                return p;
            })
            .run(context -> {
                assertThat(context).hasSingleBean(LlmGateway.class);
            });
    }

    @Test
    void deepseekV4FlashModelNameFlowsThroughConfiguration() {
        new ApplicationContextRunner()
            .withPropertyValues(
                "tiny-claw.model.enabled=true",
                "tiny-claw.model.api-key=sk-test",
                "tiny-claw.model.name=deepseek-v4-flash"
            )
            .withConfiguration(AutoConfigurations.of(TinyClawModelConfiguration.class))
            .withUserConfiguration(MeterRegistryConfig.class)
            .withBean(ChatModel.class, () -> mock(ChatModel.class))
            .withBean(TinyClawModelProperties.class, () -> {
                TinyClawModelProperties p = new TinyClawModelProperties();
                p.setEnabled(true);
                p.setApiKey("sk-test");
                p.setName("deepseek-v4-flash");
                return p;
            })
            .run(context -> {
                TinyClawModelProperties props = context.getBean(TinyClawModelProperties.class);
                assertThat(props.getName()).isEqualTo("deepseek-v4-flash");
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
