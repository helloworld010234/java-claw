package com.tinyclaw.domain.common;

/**
 * Domain 层异常基类。
 *
 * <p>所有 domain 包中的校验失败、状态转换非法等均通过此类或其子类抛出。
 * 属于 unchecked exception，便于在纯 domain 代码中 fail-fast。</p>
 */
public class TinyClawDomainException extends RuntimeException {

    public TinyClawDomainException(String message) {
        super(message);
    }
}
