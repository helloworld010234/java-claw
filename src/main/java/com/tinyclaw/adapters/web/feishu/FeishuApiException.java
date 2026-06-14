package com.tinyclaw.adapters.web.feishu;

/**
 * Runtime exception for Feishu OpenAPI failures.
 *
 * <p>Carries an optional Feishu business error code so callers can distinguish
 * token/auth errors from other failures without parsing exception messages.</p>
 */
public class FeishuApiException extends RuntimeException {

    private final Integer code;

    /**
     * Creates an exception for a transport or parsing failure with no Feishu code.
     *
     * @param message safe, non-sensitive description
     */
    public FeishuApiException(String message) {
        super(message);
        this.code = null;
    }

    /**
     * Creates an exception wrapping an underlying cause.
     *
     * @param message safe, non-sensitive description
     * @param cause   the original failure
     */
    public FeishuApiException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
    }

    /**
     * Creates an exception for a Feishu business error.
     *
     * @param message safe, non-sensitive description
     * @param code    the Feishu response code
     */
    public FeishuApiException(String message, int code) {
        super(message);
        this.code = code;
    }

    /**
     * Returns the Feishu business error code, or {@code null} if not applicable.
     *
     * @return Feishu code or null
     */
    public Integer getCode() {
        return code;
    }
}
