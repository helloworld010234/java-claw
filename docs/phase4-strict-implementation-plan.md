# M4 严格实施计划：生产化与可观测性

> **目标**：补齐生产环境所需的运维能力 — REST API、安全认证、自定义 Metrics、Health Check、性能基准测试。
> **工期**：3-4 天（含编码 + 单元测试 + 集成测试 + 代码审查 + 反复打磨）。
> **前提**：M1/M2/M3 全部通过，全量测试 650/0/0，无回归。

---

## 一、总体目标与范围

### 1.1 本期交付物

| 子任务 | 内容 | 优先级 |
|--------|------|--------|
| M4.1 | REST API 端点（启动 Run、查询状态、审批、消息历史） | P0 |
| M4.2 | 安全认证集成（API Key / Basic Auth） | P0 |
| M4.3 | 可观测性增强（自定义 Metrics + Health Check） | P1 |
| M4.4 | 性能基准测试（JMH 工具并发 vs 顺序对比） | P1 |

### 1.2 本期不做

- OAuth2 / JWT（超出范围，后续按需扩展）
- SSE 流式输出（长时任务当前用轮询状态接口）
- Grafana Dashboard JSON（仅暴露 Metrics，可视化由运维侧负责）
- 数据库 Schema 变更（本期纯适配器层新增，零 Schema 变更）

---

## 二、架构铁律（代码质量红线）

### 2.1 六边形分层约束

```
┌─────────────────────────────────────────┐
│  adapters/web/        ← HTTP 协议转换   │  允许 Spring MVC 注解
│  adapters/observability ← Metrics/Health │  允许 Micrometer/Spring Boot Actuator
├─────────────────────────────────────────┤
│  application/run/     ← 业务编排        │  零 Spring 注解
│  application/approval/← 审批业务        │  零 Spring 注解
├─────────────────────────────────────────┤
│  domain/              ← 领域模型        │  零框架依赖
├─────────────────────────────────────────┤
│  ports/               ← 端口接口         │  零框架依赖
└─────────────────────────────────────────┘
```

**红线**：
- `domain/` 和 `ports/` 层**绝对禁止**引入 `org.springframework.*`、`io.micrometer.*`、`jakarta.servlet.*` 等框架依赖。
- `application/` 层**绝对禁止**使用 `@Autowired`、`@Component`、`@RestController` 等 Spring 注解。
- `adapters/web/` 的 Controller **只负责 HTTP 协议转换**，业务逻辑必须委托给 `application/` 层已有的 Service（`AgentRunExecutionService`、`ApprovalResumeService`）。
- DTO 定义在 `adapters/web/dto/` 包，**不得污染 domain 层**。

### 2.2 向后兼容约束

- `application.yml` 新增配置项必须有默认值，旧配置不修改时行为不变。
- `AgentEngine` 构造函数链保留所有旧签名，新增参数放在末尾并允许 null（自动降级为 NoOp）。
- REST API 的异步执行使用 `CompletableFuture` 或 `DeferredResult`，不得阻塞 HTTP 线程池。

### 2.3 测试覆盖率红线

| 层级 | 行覆盖率 | 分支覆盖率 | 验证方式 |
|------|---------|-----------|---------|
| domain | ≥ 95% | ≥ 90% | 单元测试 |
| application | ≥ 90% | ≥ 85% | 单元测试 + 集成测试 |
| adapters/web | ≥ 85% | ≥ 80% | `@SpringBootTest` + `MockMvc` |
| adapters/observability | ≥ 90% | ≥ 85% | 单元测试 |

**不达标不允许通过**：任何新增类的行覆盖率 < 85% 或分支覆盖率 < 80%，必须补充测试直至达标。

### 2.4 代码风格红线

- 所有新增类必须有 Javadoc（类级 + 公共方法级）。
- 所有 `public` 方法参数必须使用 `DomainGuards.requireNonNull` / `requireNonBlank` 校验。
- 所有异常路径必须有日志记录（`log.warn` / `log.error`）。
- 禁止在业务代码中硬编码字符串（使用常量或配置注入）。

---

## 三、子任务实施计划

### M4.1 REST API 端点

#### 3.1.1 实施步骤

**Step 1: DTO 定义（30 min）**

```
adapters/web/dto/
├── StartRunRequest.java          # POST /api/v1/runs 请求体
│   ├── sessionId: String          # 可选，为空时自动创建
│   ├── prompt: String             # 必填，非空校验
│   ├── workDir: String            # 可选，默认当前目录
│   └── maxTurns: int              # 可选，默认 20
├── RunStatusResponse.java         # GET /api/v1/runs/{runId} 响应
│   ├── runId: String
│   ├── status: String             # RUNNING / COMPLETED / FAILED / WAITING_APPROVAL
│   ├── turnCount: int
│   ├── errorReason: String        # 失败时非空
│   └── createdAt: Instant
├── ApprovalActionRequest.java     # POST /api/v1/approvals/{id}/action 请求体
│   └── action: String             # "approve" 或 "reject"
└── ApiErrorResponse.java          # 统一错误响应
    ├── code: String
    ├── message: String
    └── timestamp: Instant
```

**Step 2: Controller 实现（2-3 h）**

```
adapters/web/
├── AgentRunController.java         # POST /api/v1/runs
├── AgentRunStatusController.java   # GET /api/v1/runs/{runId}
├── ApprovalController.java         # POST /api/v1/approvals/{id}/action
├── SessionController.java          # GET /api/v1/sessions/{id}/messages
└── GlobalExceptionHandler.java     # @ControllerAdvice 统一异常处理
```

**AgentRunController** 关键设计：
- 接收 `StartRunRequest`，委托 `AgentRunExecutionService.execute()` 执行。
- 使用 `CompletableFuture<AgentRunResult>` 异步执行，HTTP 层立即返回 `runId` + `ACCEPTED` 状态。
- 客户端通过 `GET /api/v1/runs/{runId}` 轮询状态。
- 如果 `approval.enabled = true` 且工具触发审批，返回 `202 ACCEPTED` + `WAITING_APPROVAL` 状态。

**ApprovalController** 关键设计：
- 接收 `ApprovalActionRequest`，委托 `ApprovalResumeService.resume()` 或 `reject()`。
- 审批通过后，后台异步继续执行剩余的 Agent Run。

**GlobalExceptionHandler** 关键设计：
- 捕获 `TinyClawDomainException` → 400 Bad Request
- 捕获 `LlmException` → 502 Bad Gateway
- 捕获 `AccessDeniedException` → 403 Forbidden
- 捕获其他异常 → 500 Internal Server Error
- 所有响应统一包装为 `ApiErrorResponse`

**Step 3: 集成测试（2-3 h）**

```
src/test/java/com/tinyclaw/adapters/web/
├── AgentRunControllerTest.java
├── AgentRunStatusControllerTest.java
├── ApprovalControllerTest.java
├── SessionControllerTest.java
└── GlobalExceptionHandlerTest.java
```

使用 `@SpringBootTest` + `MockMvc` + `@MockBean` 模拟底层 Service。

#### 3.1.2 验收标准（不达标不允许通过）

| # | 验收项 | 验证方式 | 通过标准 |
|---|--------|---------|---------|
| 1 | POST /api/v1/runs 启动 Run | MockMvc + FakeLlmGateway | 返回 202 + runId，状态为 RUNNING |
| 2 | GET /api/v1/runs/{runId} 查询状态 | MockMvc + @MockBean RunRepositoryPort | 返回正确状态 JSON |
| 3 | 审批接口与 ApprovalResumeService 集成 | MockMvc + @MockBean | approve 后状态变为 RESUMED，reject 后变为 REJECTED |
| 4 | GET /api/v1/sessions/{id}/messages | MockMvc + @MockBean MessageRepositoryPort | 返回消息历史列表 |
| 5 | 统一异常处理 | MockMvc 触发各种异常 | 所有异常返回标准 ApiErrorResponse JSON，HTTP 状态码正确 |
| 6 | 异步不阻塞 | 使用 CompletableFuture | HTTP 线程在 100ms 内返回，不等待 LLM 调用完成 |
| 7 | DTO 校验 | 发送非法请求 | 缺失必填字段返回 400，字段格式错误返回 400 |
| 8 | 代码覆盖率 | JaCoCo | Controller 类行覆盖率 ≥ 85%，分支覆盖率 ≥ 80% |

---

### M4.2 安全认证集成

#### 3.2.1 实施步骤

**Step 1: 配置设计（30 min）**

```yaml
# application.yml 新增
tiny-claw:
  security:
    enabled: true                    # 默认 true，生产环境必须开启
    mode: api-key                    # api-key 或 basic-auth
    api-key:
      user-key: ${TINyclaw_API_KEY:}  # 普通操作 Key
      admin-key: ${TINyclaw_ADMIN_KEY:} # 审批操作需要 Admin Key
    basic-auth:
      username: ${TINyclaw_USER:admin}
      password: ${TINyclaw_PASS:}    # 空密码时禁用 basic-auth
```

**Step 2: 安全组件实现（2-3 h）**

```
config/
└── SecurityConfiguration.java      # Spring Security 配置

adapters/web/
└── ApiKeyAuthFilter.java           # API Key 认证 Filter（extends OncePerRequestFilter）
```

**SecurityConfiguration** 关键设计：
- 使用 `SecurityFilterChain`（Spring Security 6.x 新 API）。
- `/actuator/**` 允许匿名访问（Health Check 需要）。
- `/api/v1/**` 需要认证。
- CSRF 禁用（API 场景无 Session）。
- 审批操作（`/api/v1/approvals/**`）需要 `ADMIN` 角色。

**ApiKeyAuthFilter** 关键设计：
- 从 Header `X-API-Key` 读取 Key。
- 与配置中的 `user-key` / `admin-key` 比对。
- 匹配 `admin-key` 时授予 `ROLE_ADMIN`。
- Key 为空或错误时返回 401，不抛异常（避免堆栈泄露）。

**Step 3: 集成测试（1-2 h）**

```
src/test/java/com/tinyclaw/adapters/web/
└── SecurityIntegrationTest.java
```

测试场景：
- 无 API Key → 401
- 正确 User Key → 200（普通接口），403（审批接口）
- 正确 Admin Key → 200（所有接口）
- 错误 Key → 401
- `/actuator/health` → 200（匿名允许）

#### 3.2.2 验收标准（不达标不允许通过）

| # | 验收项 | 验证方式 | 通过标准 |
|---|--------|---------|---------|
| 1 | 无 Key 请求被拒绝 | MockMvc | 返回 401，响应体不含堆栈信息 |
| 2 | User Key 可访问普通接口 | MockMvc | 返回 200 |
| 3 | User Key 不可访问审批接口 | MockMvc | 返回 403 |
| 4 | Admin Key 可访问所有接口 | MockMvc | 返回 200 |
| 5 | Actuator 端点匿名可访问 | MockMvc | `/actuator/health` 返回 200（无 Key） |
| 6 | 配置关闭安全模式 | application.yml `enabled: false` | 所有接口无需认证即可访问 |
| 7 | 代码覆盖率 | JaCoCo | SecurityConfiguration + ApiKeyAuthFilter 行覆盖率 ≥ 85% |

---

### M4.3 可观测性增强

#### 3.3.1 实施步骤

**Step 1: 自定义 Metrics（2 h）**

```
adapters/observability/
├── AgentMetrics.java               # 自定义 Metrics 注册器
└── HealthIndicators.java           # LLM 和数据库 Health Check

config/
└── MetricsConfiguration.java       # MeterFilter、自定义 Tags
```

**AgentMetrics** 关键设计：
- 使用 `MeterRegistry`（Micrometer 核心接口，不依赖 Spring）。
- 在 `AgentRunExecutionService.execute()` 前后埋点：
  - `tinyclaw.runs.total` (Counter, tag: status=running|completed|failed)
  - `tinyclaw.tools.executed` (Counter, tag: tool_name, status=success|failure)
  - `tinyclaw.llm.latency` (Timer, tag: phase=thinking|action)
  - `tinyclaw.context.size` (Gauge, 当前 session 消息数)
- 在 `ToolRegistry.execute()` 前后埋点工具执行计数。

**HealthIndicators** 关键设计：
- `LlmHealthIndicator`：向 LLM 发送一个极简 ping 请求（如 "hello"），验证连通性。
- `DatabaseHealthIndicator`：执行 `SELECT 1` 验证数据库连通性。
- 两者均实现 `HealthIndicator` 接口（Spring Boot Actuator 标准）。

**MetricsConfiguration** 关键设计：
- 使用 `MeterFilter` 统一添加 `application` tag。
- 配置 common tags：`app=java-claw`, `version=${project.version}`。

**Step 2: 集成到业务代码（1 h）**

修改点：
- `AgentRunExecutionService.execute()` — 在方法入口/出口记录 `tinyclaw.runs.total` 和 `tinyclaw.llm.latency`。
- `ToolRegistry.execute()` — 在工具执行前后记录 `tinyclaw.tools.executed`。
- `AgentEngine.run()` — 在 Thinking/Action 阶段记录 `tinyclaw.llm.latency`。

**Step 3: 测试（1-2 h）**

```
src/test/java/com/tinyclaw/adapters/observability/
├── AgentMetricsTest.java
├── HealthIndicatorsTest.java
└── MetricsConfigurationTest.java
```

使用 `SimpleMeterRegistry`（Micrometer 提供的内存注册表）验证计数器/计时器/仪表盘。

#### 3.3.2 验收标准（不达标不允许通过）

| # | 验收项 | 验证方式 | 通过标准 |
|---|--------|---------|---------|
| 1 | Run 计数器正确累加 | SimpleMeterRegistry | 每次 execute() 后 `tinyclaw.runs.total` 增加 1，tag 正确 |
| 2 | 工具执行计数器正确 | SimpleMeterRegistry | 每次 tool execute 后 `tinyclaw.tools.executed` 增加 1，tool_name tag 正确 |
| 3 | LLM 延迟计时器记录 | SimpleMeterRegistry | Thinking/Action 阶段均有 Timer 记录，duration > 0 |
| 4 | 上下文大小仪表盘 | SimpleMeterRegistry | Gauge 值等于当前 session 消息数 |
| 5 | LLM Health Check | MockMvc + @MockBean LlmGateway | `/actuator/health/llm` 返回 UP（连通）或 DOWN（断开） |
| 6 | 数据库 Health Check | MockMvc | `/actuator/health/db` 返回 UP |
| 7 | Prometheus 端点暴露 | MockMvc | `/actuator/prometheus` 包含所有自定义 Metrics |
| 8 | 代码覆盖率 | JaCoCo | AgentMetrics + HealthIndicators 行覆盖率 ≥ 90% |

---

### M4.4 性能基准测试

#### 3.4.1 实施步骤

**Step 1: JMH 依赖与基础结构（30 min）**

```xml
<!-- pom.xml 新增 test scope 依赖 -->
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>
    <version>1.37</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-annprocess</artifactId>
    <version>1.37</version>
    <scope>test</scope>
</dependency>
```

**Step 2: 基准测试类（2 h）**

```
src/test/java/com/tinyclaw/benchmark/
├── ToolExecutionBenchmark.java     # 并发 vs 顺序工具执行对比
├── LlmGatewayBenchmark.java        # LLM 装饰器开销测试
└── ContextCompactorBenchmark.java  # 上下文压缩前后 Token 数对比
```

**ToolExecutionBenchmark** 关键设计：
- 创建 3 个模拟工具（每个 sleep 100ms）。
- 基准 1：顺序执行（预期 300ms+）。
- 基准 2：Virtual Threads 并发执行（预期 ~100ms）。
- 验证并发节省 ≥ 60% 时间。

**LlmGatewayBenchmark** 关键设计：
- 基准 1：直接调用 FakeLlmGateway（无装饰器）。
- 基准 2：调用 RetryingLlmGateway（0 重试，仅包装开销）。
- 验证装饰器开销 < 5ms。

**ContextCompactorBenchmark** 关键设计：
- 构造 100 条消息的上下文。
- 基准 1：压缩前总字符数。
- 基准 2：压缩后总字符数。
- 验证压缩率 > 30%。

**Step 3: 运行与报告（30 min）**

```bash
mvn test-compile exec:java -Dexec.mainClass="com.tinyclaw.benchmark.ToolExecutionBenchmark"
```

生成基准测试报告，记录到 `docs/benchmarks/m4-benchmark-report.md`。

#### 3.4.2 验收标准（不达标不允许通过）

| # | 验收项 | 验证方式 | 通过标准 |
|---|--------|---------|---------|
| 1 | 并发工具执行节省 ≥ 60% | JMH | 3 个 100ms 工具并发总耗时 < 150ms |
| 2 | LLM 装饰器开销 < 5ms | JMH | RetryingLlmGateway 包装开销 < 5ms |
| 3 | 上下文压缩率 > 30% | JMH | 100 条消息压缩后字符数减少 > 30% |
| 4 | 基准测试可重复运行 | mvn 命令 | `mvn test-compile` 后基准测试类可编译通过 |
| 5 | 报告文档 | docs/benchmarks/ | 存在 `m4-benchmark-report.md`，含具体数据 |

---

## 四、实施顺序与依赖

```
Day 1: M4.1 REST API
  ├── 上午: DTO + Controller 骨架
  ├── 下午: Controller 实现 + GlobalExceptionHandler
  └── 晚上: 集成测试 + 覆盖率检查

Day 2: M4.2 安全认证
  ├── 上午: SecurityConfiguration + ApiKeyAuthFilter
  ├── 下午: 集成测试（SecurityIntegrationTest）
  └── 晚上: 与 M4.1 联调（带认证的 API 测试）

Day 3: M4.3 可观测性
  ├── 上午: AgentMetrics + 业务代码埋点
  ├── 下午: HealthIndicators + MetricsConfiguration
  └── 晚上: 测试 + 覆盖率检查

Day 4: M4.4 基准测试 + 打磨
  ├── 上午: JMH 依赖 + 基准测试类
  ├── 下午: 运行基准测试 + 生成报告
  └── 晚上: 全量测试 + 覆盖率检查 + 代码审查
```

**依赖关系**：
- M4.2 依赖 M4.1（安全认证需要保护 API 端点）。
- M4.3 可独立进行，但建议与 M4.1 联调（验证 Metrics 在 API 调用时正确记录）。
- M4.4 依赖 M4.1/M4.3（需要完整的业务代码进行基准测试）。

---

## 五、代码审查 Checklist

### 5.1 架构审查

- [ ] `adapters/web/` 的 Controller 是否只负责 HTTP 协议转换？
- [ ] Controller 是否直接调用了 `application/` 层的 Service？
- [ ] DTO 是否只在 `adapters/web/dto/` 中定义？
- [ ] `domain/` 和 `ports/` 层是否零 Spring 依赖？
- [ ] `application/` 层是否零 Spring 注解？

### 5.2 安全审查

- [ ] API Key 是否从环境变量或配置文件读取，不硬编码？
- [ ] 错误响应是否不包含堆栈信息或敏感配置？
- [ ] Admin Key 是否与普通 Key 分离？
- [ ] `/actuator/**` 是否允许匿名访问？

### 5.3 可观测性审查

- [ ] Metrics 命名是否符合 Micrometer 规范（`tinyclaw.*` 小写 + 下划线）？
- [ ] Health Check 是否不阻塞（超时 < 5s）？
- [ ] 业务代码埋点是否通过 `TraceReporter` 端口抽象（不直接依赖 Micrometer）？

### 5.4 性能审查

- [ ] REST API 是否使用异步响应（不阻塞 HTTP 线程）？
- [ ] Health Check 的 LLM ping 是否使用极简请求（避免消耗 Token）？
- [ ] Metrics 埋点是否使用 `Timer.Sample`（避免每次创建对象的开销）？

---

## 六、风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| **REST API 长时任务阻塞 HTTP 线程** | 🔴 高 | 强制使用 `CompletableFuture` 异步执行；集成测试验证 HTTP 响应时间 < 100ms |
| **Spring Security 配置与现有 CLI 模式冲突** | 🔴 高 | `security.enabled` 配置开关；CLI 模式下自动禁用 Security Filter |
| **Metrics 埋点污染业务代码** | 🟡 中 | 通过 `TraceReporter` 端口抽象；业务代码只调用端口方法 |
| **JMH 基准测试在 CI 环境不稳定** | 🟡 中 | 基准测试标记为 `@DisabledIfEnvironmentVariable`（CI 环境跳过）；本地手动运行 |
| **API Key 泄露到日志** | 🔴 高 | ApiKeyAuthFilter 中 Key 比对后立刻清除；日志中不打印 Key 内容 |

---

## 七、文件变更总览

### 新增文件

```
[adapters/web/]
├── AgentRunController.java
├── AgentRunStatusController.java
├── ApprovalController.java
├── SessionController.java
├── GlobalExceptionHandler.java
├── dto/
│   ├── StartRunRequest.java
│   ├── RunStatusResponse.java
│   ├── ApprovalActionRequest.java
│   └── ApiErrorResponse.java
└── ApiKeyAuthFilter.java

[adapters/observability/]
├── AgentMetrics.java
└── HealthIndicators.java

[config/]
├── SecurityConfiguration.java
└── MetricsConfiguration.java

[src/test/java/com/tinyclaw/adapters/web/]
├── AgentRunControllerTest.java
├── AgentRunStatusControllerTest.java
├── ApprovalControllerTest.java
├── SessionControllerTest.java
├── GlobalExceptionHandlerTest.java
└── SecurityIntegrationTest.java

[src/test/java/com/tinyclaw/adapters/observability/]
├── AgentMetricsTest.java
├── HealthIndicatorsTest.java
└── MetricsConfigurationTest.java

[src/test/java/com/tinyclaw/benchmark/]
├── ToolExecutionBenchmark.java
├── LlmGatewayBenchmark.java
└── ContextCompactorBenchmark.java

[docs/benchmarks/]
└── m4-benchmark-report.md
```

### 修改文件

```
[application/run/]
└── AgentRunExecutionService.java          # 新增 Metrics 埋点

[application/tool/]
└── ToolRegistry.java                     # 新增工具执行计数埋点

[application/engine/]
└── AgentEngine.java                      # 新增 LLM 延迟计时埋点

[config/]
└── EngineConfiguration.java              # 注入 AgentMetrics

[pom.xml]
└── 新增 JMH 依赖（test scope）

[src/main/resources/application.yml]
└── 新增 security 和 metrics 配置段
```

---

## 八、验收总表

| 子任务 | 测试数 | 行覆盖率 | 分支覆盖率 | 全量测试 | 状态 |
|--------|--------|---------|-----------|---------|------|
| M4.1 REST API | ≥ 20 | ≥ 85% | ≥ 80% | 0 失败 | ⬜ |
| M4.2 安全认证 | ≥ 10 | ≥ 85% | ≥ 80% | 0 失败 | ⬜ |
| M4.3 可观测性 | ≥ 15 | ≥ 90% | ≥ 85% | 0 失败 | ⬜ |
| M4.4 基准测试 | ≥ 3 | N/A | N/A | 编译通过 | ⬜ |
| **全量合计** | **≥ 48** | **≥ 90%** | **≥ 85%** | **0 失败** | **⬜** |

**通过标准**：
1. 全量测试 `mvn test` 0 失败（含新增测试）。
2. JaCoCo 报告总覆盖率 ≥ 90%（行）/ ≥ 85%（分支）。
3. 每个新增类的覆盖率 ≥ 85%（行）/ ≥ 80%（分支），不达标必须补充测试。
4. 代码审查 Checklist 全部通过。
5. 基准测试报告存在且数据达标。

**任何一项不达标，必须反复打磨直至通过。**
