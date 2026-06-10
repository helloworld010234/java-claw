-- ============================================================
-- V2: 补充 Agent Run 审计字段
-- ============================================================

-- agent_runs 补充 mode、prompt 和 completed_at（支持按模式/提示词查询及审计时间戳）
ALTER TABLE agent_runs ADD COLUMN mode VARCHAR(32);
ALTER TABLE agent_runs ADD COLUMN prompt TEXT;
ALTER TABLE agent_runs ADD COLUMN completed_at TIMESTAMP;

-- tool_executions 补充起止时间戳（支持精确耗时审计）
ALTER TABLE tool_executions ADD COLUMN started_at TIMESTAMP;
ALTER TABLE tool_executions ADD COLUMN completed_at TIMESTAMP;
