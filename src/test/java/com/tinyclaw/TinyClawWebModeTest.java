package com.tinyclaw;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Web 模式启动测试。
 *
 * <p>验证不传 CLI 子命令时，应用能作为 Spring Boot Web 应用正常启动，
 * 且不会触发 Picocli unknown option 异常。</p>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    args = {"--server.port=0"}
)
@ActiveProfiles("test")
class TinyClawWebModeTest {

    @Test
    void contextLoads() {
        // 上下文成功加载即通过，证明 Web 模式未被 CliRunner 误杀
    }
}
