package com.tinyclaw.adapters.web.feishu;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuApiExceptionTest {

    @Test
    void messageOnlyConstructor() {
        FeishuApiException e = new FeishuApiException("something failed");

        assertThat(e.getMessage()).isEqualTo("something failed");
        assertThat(e.getCode()).isNull();
    }

    @Test
    void messageAndCauseConstructor() {
        Throwable cause = new RuntimeException("boom");
        FeishuApiException e = new FeishuApiException("wrapped", cause);

        assertThat(e.getMessage()).isEqualTo("wrapped");
        assertThat(e.getCause()).isSameAs(cause);
        assertThat(e.getCode()).isNull();
    }

    @Test
    void messageAndCodeConstructor() {
        FeishuApiException e = new FeishuApiException("auth failed", 99991663);

        assertThat(e.getMessage()).isEqualTo("auth failed");
        assertThat(e.getCode()).isEqualTo(99991663);
    }
}
