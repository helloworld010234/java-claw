package com.tinyclaw.domain.common;

/**
 * Domain 层通用校验工具类。
 *
 * <p>提供 fail-fast 的静态校验方法，减少 domain 模型中的重复校验代码。
 * 所有方法在校验失败时抛出 {@link TinyClawDomainException}。</p>
 */
public final class DomainGuards {

    private DomainGuards() {
        // utility class
    }

    /**
     * 要求字符串非 null 且非 blank（trim 后长度 > 0）。
     */
    public static String requireNonBlank(String value, String fieldName) {
        if (value == null) {
            throw new TinyClawDomainException(fieldName + " must not be null");
        }
        if (value.isBlank()) {
            throw new TinyClawDomainException(fieldName + " must not be blank");
        }
        return value;
    }

    /**
     * 要求值非 null。
     */
    public static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new TinyClawDomainException(fieldName + " must not be null");
        }
        return value;
    }

    /**
     * 要求 int 值 >= 0。
     */
    public static int requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new TinyClawDomainException(fieldName + " must not be negative, was " + value);
        }
        return value;
    }

    /**
     * 要求 int 值 > 0。
     */
    public static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new TinyClawDomainException(fieldName + " must be positive, was " + value);
        }
        return value;
    }
}
