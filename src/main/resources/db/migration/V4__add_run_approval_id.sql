-- ============================================================
-- V4: Add approval_id to agent_runs for WAITING_APPROVAL state
-- ============================================================

ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS approval_id VARCHAR(36);
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_runs_approval_id ON agent_runs(approval_id);
