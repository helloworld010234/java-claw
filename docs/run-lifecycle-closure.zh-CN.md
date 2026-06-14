# Agent Run 生命周期闭环与可观测持久化

## 背景与目标

本阶段目标是将 Java Claw 的 Agent Run 生命周期（run → message → tool → usage）形成可信闭环，消除代码审查中发现的持久化一致性风险。

## Go 原实现行为基线

Go 原实现通过 ReAct Loop 直接操作内存中的会话消息列表，在 run 结束时将增量消息批量写入 SQLite。Usage 通过回调聚合后一次性写入。Reporter 链路的失败不会阻断主流程。

## Java 当前实现落点

- `AgentRunExecutionService` 负责 run 生命周期编排：saveSession → saveRunStarted → restoreHistory → runEngine → persistDeltaMessages → saveRunCompleted/Failed
- `AgentEngine` 内部维护 ReAct Loop，通过 `SessionService` 操作消息，通过 `Reporter` 报告事件
- `JdbcMessageRepository` 按 runId 和 sessionId 持久化消息，sequence_number 由数据库 MAX+1 生成
- `JdbcUsageRepository` 写入 `usage_records` 表，受外键约束（run_id/session_id 必须已存在）

## 本阶段修复目标

1. **消除 catch/finally 双重 persistDeltaMessages 风险**
2. **Usage 持久化从 Reporter 链路剥离，由 AgentRunExecutionService 作为关键生命周期动作执行**
3. **CompositeReporter 不再完全静默吞异常，至少记录 warn**
4. **show-run 默认摘要包含 usage total**
5. **补齐 H2 集成测试，证明外键合法**

## 生命周期时序

```
RunCommand.runWithEngine
  ├─ AgentRunExecutionService.execute
  │   ├─ saveSession(session)                    -- session 先落库
  │   ├─ saveRunStarted(run, engineType, prompt) -- run 先落库
  │   ├─ restoreHistory(session)                 -- 从历史恢复工作内存
  │   ├─ snapshot messagesBefore                 -- 记录持久化前消息数
  │   ├─ engine.run(...)                         -- ReAct Loop 执行
  │   │   ├─ append user prompt
  │   │   ├─ LLM call(s) → accumulate totalUsage
  │   │   ├─ tool call(s) → append observations
  │   │   └─ return AgentRunResult(success, totalUsage)
  │   ├─ persistDeltaMessages(runId, messagesBefore)  -- 只执行一次
  │   ├─ saveRunCompleted/Failed(runId, ...)
  │   └─ persistUsageAggregate(runId, totalUsage)     -- run 成功后写 usage
  └─ printAgentSummary
```

### 异常路径时序

- **Engine 返回 failed result**（如 LLM 调用失败但被 engine 内部 catch）：
  - finally → persistDeltaMessages（保存已产生的 user + assistant 消息）
  - saveRunFailed
  - persistUsageAggregate（保存已汇总的 usage）

- **Engine 抛出未预期异常**（如 NullPointerException）：
  - catch → saveRunFailed
  - finally → persistDeltaMessages（保存异常前已产生的消息）
  - **不保存 usage**（因为 engine 未正常返回 totalUsage）

- **Message persistence 失败**：
  - 异常向上传播，run 状态可能不一致。这是已知限制，后续可通过事务包裹改善。

- **Usage persistence 失败**：
  - 记录 warn 日志，不阻断主流程，但失败可见。

## Usage Persistence 策略选择理由

**选择：per-run aggregate usage persistence**

- **理由 1**：减少 per-call 时序复杂度。AgentEngine 内部可能调用多次 LLM（多 turn），每次 per-call 保存需要保证 run/session 已存在，增加时序耦合。
- **理由 2**：与 Reporter 链路解耦。Reporter 是观察性组件，不应承载关键持久化职责。
- **理由 3**：show-run 只需要 aggregate summary，per-call 明细在本阶段非必需。

**实现**：AgentRunExecutionService 在 `saveRunCompleted/Failed` 之后，直接读取 `AgentRunResult.totalUsage()`，构造 `UsageRecord` 并调用 `UsageRepositoryPort.save()`。写入失败记录 warn 日志。

## Reporter 失败策略

- **CompositeReporter**：对每个 reporter 的异常记录 `warn` 日志（包含 reporter 类名和事件名）。非关键 reporter 的失败不会阻断主流程。
- **关键持久化**（message、usage、run state）**不依赖 Reporter 链路**，由 AgentRunExecutionService 直接调用 RepositoryPort。

## 本阶段不做事项

- 飞书 ChatOps 集成
- Benchmark suite
- Subagent 支持
- 完整 trace_spans 分布式追踪
- 事务包裹（message + run + usage 原子提交）
- Per-call usage 明细持久化

## 后续阶段建议

1. **事务包裹**：将 `saveSession + saveRunStarted + persistDeltaMessages + saveRunCompleted + persistUsage` 纳入同一事务。
2. **Per-call usage 明细**：如需分析单次 LLM 调用成本，可在 AgentEngine 内保留逐次记录，由 AgentRunExecutionService 批量写入。
3. **Show-run 增强**：支持 `--format json`、时间范围过滤。
4. **Flyway 迁移审查**：确认 `usage_records` 外键约束在生产 PostgreSQL 中的行为。
