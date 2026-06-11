package com.tinyclaw.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer Metrics 配置。
 *
 * <p>配置 common tags 和 MeterFilter。</p>
 */
@Configuration
public class MetricsConfiguration {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
            .commonTags("app", "java-claw")
            .meterFilter(MeterFilter.deny(id -> {
                String name = id.getName();
                // 排除 Spring 内部 Metrics，减少噪音
                return name.startsWith("jvm") || name.startsWith("system") || name.startsWith("process");
            }));
    }
}
