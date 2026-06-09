package com.tinyclaw.domain.session;

import com.tinyclaw.domain.common.DomainGuards;
import com.tinyclaw.domain.common.TinyClawDomainException;

import java.time.Instant;

/**
 * Agent 会话。
 *
 * <p>不可变值对象。状态转换通过返回新实例实现。</p>
 */
public final class Session {

    private final String id;
    private final String workDir;
    private final SessionStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Session(String id, String workDir, SessionStatus status, Instant createdAt, Instant updatedAt) {
        this.id = DomainGuards.requireNonBlank(id, "id");
        this.workDir = DomainGuards.requireNonBlank(workDir, "workDir");
        this.status = DomainGuards.requireNonNull(status, "status");
        this.createdAt = DomainGuards.requireNonNull(createdAt, "createdAt");
        this.updatedAt = DomainGuards.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new TinyClawDomainException("updatedAt must not be before createdAt");
        }
    }

    public String id() {
        return id;
    }

    public String workDir() {
        return workDir;
    }

    public SessionStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * 创建新的 ACTIVE 会话。
     */
    public static Session create(String id, String workDir, Instant now) {
        DomainGuards.requireNonNull(now, "now");
        return new Session(id, workDir, SessionStatus.ACTIVE, now, now);
    }

    /**
     * 归档会话，返回新的 ARCHIVED 实例。
     *
     * @param now 归档时间，不能早于 createdAt
     */
    public Session archive(Instant now) {
        DomainGuards.requireNonNull(now, "now");
        if (now.isBefore(createdAt)) {
            throw new TinyClawDomainException("archive time must not be before createdAt");
        }
        return new Session(id, workDir, SessionStatus.ARCHIVED, createdAt, now);
    }
}
