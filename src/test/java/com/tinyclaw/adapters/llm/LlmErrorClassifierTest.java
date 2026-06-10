package com.tinyclaw.adapters.llm;

import com.tinyclaw.ports.llm.LlmErrorType;
import com.tinyclaw.ports.llm.LlmException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class LlmErrorClassifierTest {

    @Test
    void nullCauseReturnsUnknown() {
        LlmException ex = LlmErrorClassifier.classify(null);
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.UNKNOWN);
    }

    @Test
    void alreadyTypedLlmExceptionIsReturnedAsIs() {
        LlmException original = new LlmException("msg", LlmErrorType.AUTHENTICATION);
        LlmException ex = LlmErrorClassifier.classify(original);
        assertThat(ex).isSameAs(original);
    }

    @Test
    void socketTimeoutExceptionIsTimeout() {
        LlmException ex = LlmErrorClassifier.classify(new SocketTimeoutException("Read timed out"));
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.TIMEOUT);
        assertThat(ex.getMessage()).contains("timed out");
    }

    @Test
    void timeoutExceptionIsTimeout() {
        LlmException ex = LlmErrorClassifier.classify(new TimeoutException("waited too long"));
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.TIMEOUT);
    }

    @Test
    void nestedTimeoutCauseIsTimeout() {
        RuntimeException wrapper = new RuntimeException("outer",
            new java.io.UncheckedIOException(new SocketTimeoutException("inner")));
        LlmException ex = LlmErrorClassifier.classify(wrapper);
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.TIMEOUT);
    }

    @Test
    void resourceAccessExceptionIsTransientNetwork() {
        LlmException ex = LlmErrorClassifier.classify(new ResourceAccessException("I/O error"));
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.TRANSIENT_NETWORK);
    }

    @Test
    void socketExceptionIsTransientNetwork() {
        LlmException ex = LlmErrorClassifier.classify(new SocketException("Connection reset"));
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.TRANSIENT_NETWORK);
    }

    @Test
    void ioExceptionIsTransientNetwork() {
        LlmException ex = LlmErrorClassifier.classify(new IOException("Broken pipe"));
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.TRANSIENT_NETWORK);
    }

    @Test
    void http401IsAuthentication() {
        LlmException ex = LlmErrorClassifier.classify(
            HttpClientErrorException.create(org.springframework.http.HttpStatus.UNAUTHORIZED,
                "Unauthorized", new org.springframework.http.HttpHeaders(), new byte[0], StandardCharsets.UTF_8));
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.AUTHENTICATION);
        assertThat(ex.getMessage()).contains("authentication");
    }

    @Test
    void http429IsRateLimit() {
        LlmException ex = LlmErrorClassifier.classify(
            HttpClientErrorException.create(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests", new org.springframework.http.HttpHeaders(), new byte[0], StandardCharsets.UTF_8));
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.RATE_LIMIT);
    }

    @Test
    void http400IsProviderRejected() {
        LlmException ex = LlmErrorClassifier.classify(
            HttpClientErrorException.create(org.springframework.http.HttpStatus.BAD_REQUEST,
                "Bad Request", new org.springframework.http.HttpHeaders(), new byte[0], StandardCharsets.UTF_8));
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.PROVIDER_REJECTED_REQUEST);
    }

    @Test
    void http500IsTransientNetwork() {
        LlmException ex = LlmErrorClassifier.classify(
            HttpServerErrorException.create(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error", new org.springframework.http.HttpHeaders(), new byte[0], StandardCharsets.UTF_8));
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.TRANSIENT_NETWORK);
    }

    @Test
    void messageContainingTimeoutIsTimeout() {
        LlmException ex = LlmErrorClassifier.classify(new RuntimeException("The request timed out after 30s"));
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.TIMEOUT);
    }

    @Test
    void messageContainingApiKeyIsAuthentication() {
        LlmException ex = LlmErrorClassifier.classify(new RuntimeException("Invalid api key provided"));
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.AUTHENTICATION);
    }

    @Test
    void messageContainingRateLimitIsRateLimit() {
        LlmException ex = LlmErrorClassifier.classify(new RuntimeException("Rate limit exceeded"));
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.RATE_LIMIT);
    }

    @Test
    void sanitisesApiKeyInMessage() {
        LlmException ex = LlmErrorClassifier.classify(
            new RuntimeException("api_key=sk-1234567890abcdef1234567890abcdef"));
        assertThat(ex.getMessage()).contains("<redacted>");
        assertThat(ex.getMessage()).doesNotContain("sk-1234567890abcdef1234567890abcdef");
    }

    @Test
    void sanitisesBearerTokenInMessage() {
        LlmException ex = LlmErrorClassifier.classify(
            new RuntimeException("Authorization: Bearer super-secret-token-value"));
        assertThat(ex.getMessage()).contains("<redacted>");
        assertThat(ex.getMessage()).doesNotContain("super-secret-token-value");
    }

    @Test
    void unknownExceptionIsUnknown() {
        LlmException ex = LlmErrorClassifier.classify(new RuntimeException("Something weird"));
        assertThat(ex.getErrorType()).isEqualTo(LlmErrorType.UNKNOWN);
    }
}
