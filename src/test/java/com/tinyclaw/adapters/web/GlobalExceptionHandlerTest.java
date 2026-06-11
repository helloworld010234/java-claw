package com.tinyclaw.adapters.web;

import com.tinyclaw.domain.common.TinyClawDomainException;
import com.tinyclaw.ports.llm.LlmException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用 standalone MockMvc 验证 GlobalExceptionHandler。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void domainExceptionReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/test/domain-error"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("DOMAIN_ERROR"))
            .andExpect(jsonPath("$.message").value("Invalid domain state"));
    }

    @Test
    void llmExceptionReturnsBadGateway() throws Exception {
        mockMvc.perform(get("/test/llm-error"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("LLM_ERROR"));
    }

    @Test
    void illegalArgumentReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/test/illegal-arg"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void genericExceptionReturnsInternalError() throws Exception {
        mockMvc.perform(get("/test/generic-error"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/domain-error")
        public String domainError() {
            throw new TinyClawDomainException("Invalid domain state");
        }

        @GetMapping("/llm-error")
        public String llmError() {
            throw new LlmException("LLM service down");
        }

        @GetMapping("/illegal-arg")
        public String illegalArg() {
            throw new IllegalArgumentException("Bad parameter");
        }

        @GetMapping("/generic-error")
        public String genericError() {
            throw new RuntimeException("Something went wrong");
        }
    }
}
