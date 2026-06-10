package com.tinyclaw.adapters.llm;

import com.tinyclaw.ports.llm.LlmErrorType;
import com.tinyclaw.ports.llm.LlmException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Classifies Spring AI and low-level network exceptions into {@link LlmErrorType}.
 *
 * <p>Never surfaces API keys, headers, or raw request bodies in the returned message.</p>
 */
public final class LlmErrorClassifier {

    private LlmErrorClassifier() {
        // utility
    }

    /**
     * Classifies a raw exception into a typed {@link LlmException}.
     *
     * @param cause the original exception from Spring AI or the HTTP layer
     * @return a new LlmException with a sanitised message and categorised type
     */
    public static LlmException classify(Throwable cause) {
        if (cause == null) {
            return new LlmException("Unknown LLM failure", LlmErrorType.UNKNOWN);
        }

        if (cause instanceof LlmException le) {
            return le;
        }

        LlmErrorType type = detectType(cause);
        String message = sanitiseMessage(cause, type);
        return new LlmException(message, cause, type);
    }

    private static LlmErrorType detectType(Throwable cause) {
        if (cause instanceof SocketTimeoutException
            || cause instanceof TimeoutException
            || isCausedBy(cause, SocketTimeoutException.class)) {
            return LlmErrorType.TIMEOUT;
        }

        if (cause instanceof ResourceAccessException
            || cause instanceof SocketException
            || cause instanceof IOException
            || isCausedBy(cause, IOException.class)) {
            return LlmErrorType.TRANSIENT_NETWORK;
        }

        if (cause instanceof HttpClientErrorException hce) {
            HttpStatus status = HttpStatus.resolve(hce.getStatusCode().value());
            if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
                return LlmErrorType.AUTHENTICATION;
            }
            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                return LlmErrorType.RATE_LIMIT;
            }
            if (status == HttpStatus.BAD_REQUEST || status == HttpStatus.UNPROCESSABLE_ENTITY) {
                return LlmErrorType.PROVIDER_REJECTED_REQUEST;
            }
            return LlmErrorType.PROVIDER_REJECTED_REQUEST;
        }

        if (cause instanceof HttpServerErrorException) {
            return LlmErrorType.TRANSIENT_NETWORK;
        }

        if (cause instanceof TransientAiException) {
            return LlmErrorType.TRANSIENT_NETWORK;
        }

        if (cause instanceof NonTransientAiException) {
            return LlmErrorType.PROVIDER_REJECTED_REQUEST;
        }

        String msg = cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return LlmErrorType.TIMEOUT;
        }
        if (msg.contains("api key") || msg.contains("apikey") || msg.contains("authentication") || msg.contains("unauthorized")) {
            return LlmErrorType.AUTHENTICATION;
        }
        if (msg.contains("rate limit") || msg.contains("too many requests") || msg.contains("rate_limit")) {
            return LlmErrorType.RATE_LIMIT;
        }
        if (msg.contains("connect") || msg.contains("network") || msg.contains("socket") || msg.contains("ioexception")) {
            return LlmErrorType.TRANSIENT_NETWORK;
        }

        return LlmErrorType.UNKNOWN;
    }

    private static String sanitiseMessage(Throwable cause, LlmErrorType type) {
        String raw = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();

        // Strip anything that looks like an API key, Authorization header, or standalone token
        String cleaned = raw
            // api_key=..., api-key=..., apikey=... (with or without separator)
            .replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)[^\\s&,)\"']{8,}", "$1<redacted>")
            // Authorization: Bearer ... (replace only the token part, keep header name)
            .replaceAll("(?i)(authorization\\s*[:=]\\s+(?:bearer\\s+))[^\\s&,)\"']+", "$1<redacted>")
            // standalone bearer token (e.g. in JSON bodies or query strings)
            .replaceAll("(?i)(\"?bearer\"?\\s*[:=]?\\s*)[^\\s&,)\"']+", "$1<redacted>")
            // standalone sk- prefixed keys (common OpenAI / DeepSeek key format)
            .replaceAll("(?i)(sk-[a-zA-Z0-9_-]{10,})", "<redacted>");

        return switch (type) {
            case TIMEOUT -> "LLM request timed out";
            case AUTHENTICATION -> "LLM authentication failed: " + cleaned;
            case RATE_LIMIT -> "LLM rate limit exceeded: " + cleaned;
            case TRANSIENT_NETWORK -> "LLM network error: " + cleaned;
            case PROVIDER_REJECTED_REQUEST -> "LLM provider rejected request: " + cleaned;
            case CONFIGURATION -> "LLM configuration error: " + cleaned;
            case UNKNOWN -> "LLM call failed: " + cleaned;
        };
    }

    private static boolean isCausedBy(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable.getCause();
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
