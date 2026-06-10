-- ============================================================
-- V3: Recreate approval_requests with schema aligned to domain model
-- ============================================================

-- The old table from V1 is not yet used by any application code.
-- Drop and recreate to align columns with the ApprovalRequest domain model.
DROP TABLE IF EXISTS approval_requests;

CREATE TABLE approval_requests (
    id                VARCHAR(36) PRIMARY KEY,
    run_id            VARCHAR(36)  NOT NULL,
    session_id        VARCHAR(36)  NOT NULL,
    tool_call_id      VARCHAR(128) NOT NULL,
    tool_name         VARCHAR(128) NOT NULL,
    arguments_preview VARCHAR(1000) NOT NULL,
    status            VARCHAR(32)  NOT NULL,
    decision_reason   VARCHAR(500),
    requested_at      TIMESTAMP    NOT NULL,
    decided_at        TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_approval_requests_run_id ON approval_requests(run_id);
CREATE INDEX idx_approval_requests_status ON approval_requests(status);
CREATE UNIQUE INDEX uk_approval_requests_run_tool_call ON approval_requests(run_id, tool_call_id);
