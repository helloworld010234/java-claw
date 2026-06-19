# java-claw

> 基于 Java / Spring Boot 3 实现的 ReAct 智能体（Agent）运行框架，是 `go-tiny-claw` 的 Java 版本。

[![JDK](https://img.shields.io/badge/JDK-21+-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.14-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/Maven-3.9+-orange.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

---

## 目录

- [项目简介](#项目简介)
- [核心功能](#核心功能)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [快速开始](#快速开始)
  - [环境要求](#环境要求)
  - [构建项目](#构建项目)
  - [运行 CLI 示例](#运行-cli-示例)
  - [启动 Web 服务](#启动-web-服务)
- [生产环境 LLM 接入](#生产环境-llm-接入)
- [安全配置](#安全配置)
- [飞书 ChatOps](#飞书-chatops)
- [质量门禁与 CI](#质量门禁与-ci)
- [目录结构](#目录结构)
- [参与贡献](#参与贡献)
- [许可证](#许可证)

---

## 项目简介

`java-claw` 是一个可嵌入、可扩展的 **AI Agent 执行框架**，支持以 **命令行（CLI）** 或 **Web 服务** 两种形态运行。它采用经典的 **ReAct（Reasoning + Acting）** 范式，能够自主地完成多轮推理、工具调用、结果汇总与持久化，适用于自动化脚本、DevOps ChatOps、代码助手、任务执行机器人等场景。

本项目在设计上遵循 **六边形架构 / 整洁架构**，将领域模型、应用服务与基础设施彻底解耦，便于替换底层 LLM 提供商、数据库、工具集或消息通道。

---

## 核心功能

### 1. 双模运行形态

- **CLI 模式**：通过 Picocli 提供 `run`、`tool`、`runs`、`approvals` 等命令，适合本地开发、脚本集成与调试。
- **Web 模式**：提供 RESTful API、Spring Boot Actuator 监控端点，可部署为独立服务。

### 2. ReAct 智能体引擎

- 支持多轮推理与行动循环，自动决策下一步操作。
- 工具调用支持并行执行，提升执行效率。
- 内置最大轮次、最大工具调用次数与运行超时控制，防止无限循环。
- 可选的「思考阶段」用于复杂任务拆解。

### 3. 丰富的工具集

| 工具 | 功能说明 |
|------|----------|
| `shell_command` | 在受控工作区内执行 Shell 命令 |
| `read_file` | 读取指定文件内容 |
| `write_file` | 写入文件（需审批） |
| `edit_file` | 编辑文件内容（需审批） |
| `workspace_path` | 解析工作区路径 |
| `subagent_spawn` | 派生子智能体并行处理子任务 |

### 4. 危险操作审批 gate

- 对 `write_file`、`edit_file`、`shell_command` 等敏感工具自动触发审批流程。
- 支持通过 REST API 或 CLI 进行「批准 / 拒绝 / 恢复」。
- 管理员与普通用户角色分离，关键接口需 `ADMIN` 权限。

### 5. 会话与运行持久化

- 自动保存会话（Session）、运行记录（Run）、消息历史（Message）与工具执行记录。
- 支持运行失败后的历史回溯与审计。
- 聚合 LLM Token 使用量与估算成本。

### 6. 飞书 ChatOps 集成（可选）

- 通过 webhook 接收飞书消息事件。
- 支持向指定群聊发送文本通知。
- 自动缓存并刷新 `tenant_access_token`，失败时安全重试。
- 对密钥、Token、消息正文进行脱敏处理，不记录敏感信息。

### 7. 可观测性

- 内置 Micrometer 自定义指标：`tinyclaw.runs.total`、`tinyclaw.tools.executed`、`tinyclaw.llm.latency`、`tinyclaw.context.size`。
- Prometheus 端点：`/actuator/prometheus`。
- 健康检查：`/actuator/health/llm`、`/actuator/health/db`。
- OpenTelemetry 链路追踪桥接。

### 8. 开放且可替换的 LLM 接入

- 基于 **Spring AI**，默认支持 OpenAI 兼容协议。
- 可无缝接入 DeepSeek、OpenAI、Azure OpenAI、本地 vLLM 等任意兼容端点。
- 测试阶段提供 `FakeLlmGateway`，无需真实 API Key。

---

## 技术栈

| 领域 | 技术 |
|------|------|
| 语言 | Java 21 |
| 框架 | Spring Boot 3.5.14 |
| LLM 集成 | Spring AI 1.1.7（OpenAI 兼容） |
| CLI | Picocli 4.7.6 |
| 安全 | Spring Security 6 |
| 数据库 | PostgreSQL（生产）/ H2（测试） |
| 迁移 | Flyway |
| 指标 | Micrometer + Prometheus |
| 链路追踪 | Micrometer Tracing + OpenTelemetry |
| 测试 | JUnit 5、Mockito、AssertJ、Testcontainers |
| 基准测试 | JMH 1.37 |
| 覆盖率 | JaCoCo 0.8.12 |

---

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                       入口层                                  │
│              TinyClawApplication（CLI / Web 自动识别）        │
├─────────────────────────────────────────────────────────────┤
│                      应用层                                   │
│   AgentRunExecutionService    ApprovalGatePolicy             │
│   AgentEngine（ReAct 循环）    ChatOps Orchestration          │
├─────────────────────────────────────────────────────────────┤
│                      领域层                                   │
│   AgentRun / Session / Message / ApprovalRequest / Usage     │
├─────────────────────────────────────────────────────────────┤
│                      端口层                                   │
│   LlmGateway / Reporter / SessionService / AgentTool / ...   │
├─────────────────────────────────────────────────────────────┤
│                      适配器层                                 │
│   CLI │ Web │ LLM │ 工具 │ 持久化 │ 飞书 │ 可观测性          │
└─────────────────────────────────────────────────────────────┘
```

---

## 快速开始

### 环境要求

- **JDK 21 或更高版本**
- **Maven 3.9+**
- （可选）PostgreSQL 14+，用于 `dev` 环境

验证 Maven 使用的 JDK 版本：

```bash
mvn -version
```

输出中应显示 `Java version: 21.x.x` 或更高。

### 构建项目

```bash
mvn clean verify
```

> 默认测试使用 H2 内存数据库，无需真实 LLM API Key。

### 运行 CLI 示例

```bash
java -jar target/java-claw-0.0.1-SNAPSHOT.jar run \
  --prompt "Hello agent" \
  --dir . \
  --session smoke \
  --engine fake \
  --spring.profiles.active=test \
  --spring.main.web-application-type=none
```

### 启动 Web 服务

```bash
java -jar target/java-claw-0.0.1-SNAPSHOT.jar
# 或
mvn spring-boot:run
```

Web 服务默认提供：

- REST API：`/api/v1/**`
- 健康检查：`/actuator/health`
- Prometheus 指标：`/actuator/prometheus`

---

## 生产环境 LLM 接入

默认配置中 LLM 调用处于关闭状态（`tiny-claw.model.enabled=false`）。接入真实模型时，只需设置环境变量并启用模型：

```bash
# 以 DeepSeek 为例
export LLM_BASE_URL=https://api.deepseek.com
export LLM_API_KEY=your-key

java -jar target/java-claw-0.0.1-SNAPSHOT.jar \
  --tiny-claw.model.enabled=true \
  --tiny-claw.model.name=deepseek-v4-flash
```

任何兼容 OpenAI API 的端点均可使用，包括 DeepSeek、OpenAI、Azure OpenAI、本地 vLLM 等。

---

## 安全配置

- API Key 认证通过请求头 `X-API-Key` 实现。
- 管理员 Key 拥有 `ROLE_ADMIN` 角色，普通用户 Key 拥有 `ROLE_USER` 角色。
- `/api/v1/approvals/**` 仅允许管理员访问。
- 可通过 `tiny-claw.security.enabled=false` 完全关闭安全认证（仅建议本地开发使用）。
- 错误响应中不会暴露堆栈信息。

相关环境变量：

```bash
export TINyclaw_API_KEY=your-user-key
export TINyclaw_ADMIN_KEY=your-admin-key
```

---

## 飞书 ChatOps

飞书 ChatOps 默认关闭，启用后可通过 webhook 将智能体能力接入飞书群聊：

```yaml
# application.yml 或环境变量
tiny-claw:
  chatops:
    enabled: true
    appId: your-app-id
    appSecret: your-app-secret
    verifyToken: your-verify-token
    baseUrl: https://open.feishu.cn
```

> 飞书相关的 `appSecret`、`tenant_access_token`、请求体与完整消息内容均不会写入日志。

---

## 质量门禁与 CI

本项目在每个 push 和 pull request 上强制执行 P3 质量门禁：

| 门禁项 | 命令 | 阈值 |
|--------|------|------|
| 构建 + 测试 + 覆盖率 | `mvn clean verify` | 指令覆盖率 ≥ 90%，分支覆盖率 ≥ 75% |
| P1 冒烟测试 | `scripts/smoke/p1-e2e-smoke.ps1` | CLI / Web / 审批 / ChatOps 全链路 |
| 空白字符检查 | `git diff --check` | 无尾随空白或冲突标记 |
| 本地全门禁 | `scripts/ci/verify.ps1` | 以上全部 |

GitHub Actions 工作流 `.github/workflows/ci.yml` 会自动运行 Maven 验证与 P1 冒烟测试。CI 中无需真实 LLM Key 或飞书 Key。

---

## 目录结构

```
java-claw/
├── src/main/java/com/tinyclaw/
│   ├── adapters/        # 基础设施适配器（CLI、Web、LLM、工具、持久化、飞书、可观测性）
│   ├── application/     # 应用服务（审批、引擎、运行生命周期、工具注册）
│   ├── config/          # Spring 配置与安全
│   ├── domain/          # 领域模型
│   └── ports/           # 端口与 SPI 接口
├── src/test/java/com/tinyclaw/
│   ├── architecture/    # 架构边界测试
│   └── ...              # 单元与集成测试
├── src/main/resources/
│   ├── db/migration/    # Flyway 数据库迁移脚本
│   └── application*.yml # 配置文件
├── docs/                # 设计文档与架构决策记录
├── scripts/             # CI 与冒烟测试脚本
└── README.md
```

---

## 参与贡献

1. Fork 本仓库。
2. 创建功能分支：`git checkout -b feature/your-feature`。
3. 提交变更：`git commit -am 'Add some feature'`。
4. 推送分支：`git push origin feature/your-feature`。
5. 发起 Pull Request。

在提交前请确保已通过本地全门禁：

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ci/verify.ps1
```

---

## 许可证

本项目基于 [MIT License](./LICENSE) 开源。
