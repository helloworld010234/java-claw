package com.tinyclaw.config;

import com.tinyclaw.adapters.benchmark.ProcessBuilderValidationCommandRunner;
import com.tinyclaw.ports.benchmark.ValidationCommandRunnerPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for benchmark infrastructure.
 *
 * <p>Wires the real {@link ValidationCommandRunnerPort} adapter that executes
 * {@code go test} via {@link ProcessBuilder}. Tests can override this bean with
 * a fake runner so they do not depend on a local Go installation.</p>
 */
@Configuration
public class BenchmarkConfiguration {

    @Bean
    @ConditionalOnMissingBean(ValidationCommandRunnerPort.class)
    ValidationCommandRunnerPort validationCommandRunnerPort() {
        return new ProcessBuilderValidationCommandRunner();
    }
}
