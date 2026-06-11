package com.tinyclaw;

import com.tinyclaw.config.BootstrapArgs;
import com.tinyclaw.config.CliModeDetector;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Java-Claw application entry point.
 *
 * <p>Supports both CLI (Picocli) and Web modes on Spring Boot 3.x.</p>
 *
 * <p>Excludes Spring AI OpenAI auto-configurations that are not used (audio, image,
 * embedding, moderation) to prevent startup failures when the API key is absent.
 * The OpenAI chat model is created manually in {@link com.tinyclaw.config.TinyClawModelConfiguration}
 * only when {@code tiny-claw.model.enabled=true}.</p>
 */
@SpringBootApplication(exclude = {
    org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class
})
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
