package com.tinyclaw;

import com.tinyclaw.config.BootstrapArgs;
import com.tinyclaw.config.CliModeDetector;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Java-Claw application entry point.
 *
 * <p>Supports both CLI (Picocli) and Web modes on Spring Boot 3.x.</p>
 */
@SpringBootApplication
public class TinyClawApplication {

    public static void main(String[] args) {
        String[] normalizedArgs = BootstrapArgs.normalize(args);
        if (CliModeDetector.isCliMode(normalizedArgs)) {
            System.exit(SpringApplication.exit(SpringApplication.run(TinyClawApplication.class, normalizedArgs)));
        } else {
            SpringApplication.run(TinyClawApplication.class, normalizedArgs);
        }
    }
}
