package com.tinyclaw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Java-Claw 应用入口。
 *
 * <p>基于 Spring Boot 3.x 提供 CLI（Picocli）与 Web（Feishu Webhook）双模运行能力。</p>
 */
@SpringBootApplication
public class TinyClawApplication {

    public static void main(String[] args) {
        SpringApplication.run(TinyClawApplication.class, args);
    }
}
