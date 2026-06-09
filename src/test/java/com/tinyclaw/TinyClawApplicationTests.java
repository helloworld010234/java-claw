package com.tinyclaw;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring Context 加载测试。
 *
 * <p>验证 test profile 下无需真实 LLM API key 即可启动 Spring 上下文。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class TinyClawApplicationTests {

    @Test
    void contextLoads() {
        // 上下文成功加载即通过
    }
}
