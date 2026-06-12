package com.tinyclaw.ports.chatops;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatOpsSanitizerTest {

    @Test
    void nullReturnsEmpty() {
        assertThat(ChatOpsSanitizer.sanitize(null)).isEmpty();
    }

    @Test
    void blankReturnsBlank() {
        assertThat(ChatOpsSanitizer.sanitize("   ")).isEqualTo("   ");
    }

    @Test
    void emptyReturnsEmpty() {
        assertThat(ChatOpsSanitizer.sanitize("")).isEmpty();
    }

    @Test
    void noSecretsReturnsUnchanged() {
        String input = "Hello world, this is a normal message";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo(input);
    }

    @Test
    void masksApiKey() {
        String input = "api_key=sk-abc123456";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("api_key=***");
    }

    @Test
    void masksApiKeyJson() {
        String input = "{\"api_key\":\"sk-abc123456\"}";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("{\"api_key\":\"***\"}");
    }

    @Test
    void masksApiKeyHyphen() {
        String input = "api-key: sk-abc123456";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("api-key: ***");
    }

    @Test
    void masksApiKeyCompact() {
        String input = "apikey=sk-abc123456";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("apikey=***");
    }

    @Test
    void masksAuthorizationBearer() {
        String input = "Authorization: Bearer secret-token-123";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("Authorization: Bearer ***");
    }

    @Test
    void masksToken() {
        String input = "token=abc123";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("token=***");
    }

    @Test
    void masksAccessToken() {
        String input = "{\"access_token\":\"tok-12345\"}";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("{\"access_token\":\"***\"}");
    }

    @Test
    void masksRefreshToken() {
        String input = "refresh_token=rt-99999";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("refresh_token=***");
    }

    @Test
    void masksPassword() {
        String input = "password=super-secret";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("password=***");
    }

    @Test
    void masksPasswd() {
        String input = "passwd=super-secret";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("passwd=***");
    }

    @Test
    void masksPwd() {
        String input = "pwd=super-secret";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("pwd=***");
    }

    @Test
    void masksSecret() {
        String input = "secret=shhh";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("secret=***");
    }

    @Test
    void masksAppSecret() {
        String input = "app_secret=app-shhh";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("app_secret=***");
    }

    @Test
    void masksClientSecret() {
        String input = "client_secret=client-shhh";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("client_secret=***");
    }

    @Test
    void masksMultipleSecrets() {
        String input = "api_key=sk-1 and password=pwd-2 and token=tok-3";
        String sanitized = ChatOpsSanitizer.sanitize(input);
        assertThat(sanitized).doesNotContain("sk-1", "pwd-2", "tok-3");
        assertThat(sanitized).contains("api_key=***");
        assertThat(sanitized).contains("password=***");
        assertThat(sanitized).contains("token=***");
    }

    @Test
    void shortValuesNotMasked() {
        // Values shorter than 4 chars are not masked
        String input = "token=ab";
        assertThat(ChatOpsSanitizer.sanitize(input)).isEqualTo("token=ab");
    }

    @Test
    void sanitizeAndTruncateTruncatesAfterSanitize() {
        String input = "api_key=" + "s".repeat(100) + " " + "x".repeat(1000);
        String result = ChatOpsSanitizer.sanitizeAndTruncate(input, 50);
        assertThat(result).contains("***");
        assertThat(result).contains("... (truncated)");
        assertThat(result.length()).isLessThanOrEqualTo(70);
        assertThat(result).doesNotContain("s".repeat(10));
    }

    @Test
    void longSecretIsMaskedEvenWhenTruncated() {
        String input = "api_key=" + "s".repeat(200);
        String result = ChatOpsSanitizer.sanitizeAndTruncate(input, 20);
        assertThat(result).contains("***");
        assertThat(result).doesNotContain("s".repeat(10));
    }
}
