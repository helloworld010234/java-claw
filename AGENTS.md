# java-claw — AI Coding Agent Guide

> Generated from repository exploration on 2026-06-12. No previous `AGENTS.md` existed; this file is created from the actual project state.

## Project Overview

`java-claw` is a Java re-implementation of the `go-tiny-claw` agent harness. It is a Spring Boot 3 / Java 21 / Maven application that can run either as:

- a **CLI agent** (via Picocli), or
- a **web service** exposing REST APIs, Spring Boot Actuator endpoints, and an optional Feishu ChatOps webhook.

The core is a ReAct-style agent engine that orchestrates LLM calls, tool execution, persistence, approvals, and observability. The codebase follows a strict hexagonal / clean architecture with an architecture test that enforces layer boundaries.

## Technology Stack

| Area | Technology |
|---|---|
| Language | Java 21 (`<release>21</release>`) |
| Framework | Spring Boot 3.5.14 |
| LLM integration | Spring AI 1.1.7 BOM (`spring-ai-starter-model-openai`) |
| CLI | Picocli 4.7.6 (`picocli-spring-boot-starter`) |
| Security | Spring Security 6 |
| Database | PostgreSQL (runtime) + H2 (runtime/test) |
| Migrations | Flyway |
| Web | Spring MVC / Jakarta validation |
| Metrics | Micrometer + Prometheus registry + Spring Boot Actuator |
| Tracing | Micrometer Tracing + OpenTelemetry bridge |
| Testing | JUnit 5, Mockito, AssertJ, Spring Boot Test, Testcontainers (PostgreSQL) |
| Benchmarks | JMH 1.37 (test scope) |
| Coverage | JaCoCo 0.8.12 |
| Build | Maven 3.9+ |

## Build, Test and Run Commands

### Requirements

- JDK 21 or higher
- Maven 3.9+
- (Optional) PostgreSQL 14+ for the `dev` profile

### Environment check

```bash
mvn -version   # must show Java 21+
```

If your default Maven uses an older JDK, set `JAVA_HOME` explicitly before running Maven.

### Full build + tests + coverage

```bash
mvn clean verify
```

### Tests only

```bash
mvn test
# filtered example
mvn test -Dtest=AgentRunExecutionServiceTest
```

### Package the executable jar

```bash
mvn clean package
# produces target/java-claw-0.0.1-SNAPSHOT.jar
```

### Run a CLI smoke test (no real LLM key required)

```bash
java -jar target/java-claw-0.0.1-SNAPSHOT.jar run \
  --prompt "Hello agent" \
  --dir . \
  --session smoke \
  --engine fake \
  --spring.profiles.active=test \
  --spring.main.web-application-type=none
```

### Run as a web server

```bash
java -jar target/java-claw-0.0.1-SNAPSHOT.jar
# or
mvn spring-boot:run
```

### JMH benchmarks

Benchmark classes live under `src/test/java/com/tinyclaw/benchmark`. Run them from the IDE or a JMH launcher after `mvn test-compile`. JMH output files such as `benchmark-results.txt` are ignored and should not be committed.

## Code Organization

Source lives under `src/main/java/com/tinyclaw`; tests mirror the package structure under `src/test/java/com/tinyclaw`.

| Layer | Packages | Responsibility |
|---|---|---|
| Entry point | `com.tinyclaw` | `TinyClawApplication` — detects CLI vs web mode and starts Spring Boot |
| CLI adapters | `adapters/cli` | Picocli commands: `run`, `tool`, `runs`, `approvals` and their subcommands |
| Filesystem adapters | `adapters/filesystem` | Workspace guide loading |
| LLM adapters | `adapters/llm`, `adapters/llm/fake`, `adapters/llm/springai` | Gateways, decorators (retry, timeout, observe), fake gateway for tests, Spring AI adapter |
| Observability adapters | `adapters/observability` | Custom Micrometer metrics and health indicators |
| Persistence adapters | `adapters/persistence` | JDBC repositories for runs, messages, tool executions, approvals, usage |
| Reporter adapters | `adapters/reporter` | `CompositeReporter`, `ConsoleReporter`, `UsagePersistingReporter` |
| Session adapters | `adapters/session` | In-memory session store |
| Tool adapters | `adapters/tools/*` | Tool implementations: shell, read/write/edit file, workspace path, subagent spawn |
| Web adapters | `adapters/web`, `adapters/web/dto` | REST controllers, DTOs, API-key filter, global exception handler |
| Feishu adapter | `adapters/web/feishu` | ChatOps webhook controller, event parser, sender, tenant token provider, message API client, HTTP transport, DTOs |
| Workspace adapters | `adapters/workspace` | Skill / workspace loading and security |
| Application services | `application/approval`, `application/chatops`, `application/engine`, `application/run`, `application/tool` | Business logic: approval gate, ChatOps orchestration, ReAct engine, run lifecycle, tool registry |
| Configuration | `config` | Spring wiring, property classes, security, CLI mode detection |
| Domain | `domain/*` | Domain models: `AgentRun`, `Session`, `Message`, `ApprovalRequest`, `Usage`, etc. |
| Ports | `ports/*` | SPI interfaces: `LlmGateway`, repository ports, `Reporter`, `SessionService`, `AgentTool`, etc. |

Current file counts: ~149 main Java files and ~98 test Java files.

## Architecture Rules

Layer boundaries are enforced by `ArchitectureBoundaryTest` (`src/test/java/com/tinyclaw/architecture/ArchitectureBoundaryTest.java`):

- `domain/` — zero framework dependencies.
- `ports/` — zero framework dependencies; must not reference `application`, `adapters`, `config`, or Spring.
- `application/` — no Spring annotations (`@Component`, `@Autowired`, etc.); must not reference `adapters`, `config`, or Spring.
- `adapters/` and `config/` — contain Spring and framework-specific code.

Any change that violates these rules will fail the architecture test.

## Runtime Architecture

### CLI vs Web mode

`TinyClawApplication` detects CLI mode by scanning normalized args for known top-level commands: `run`, `tool`, `runs`, `approvals`. If a command is detected, Spring Boot boots and exits with the Picocli return code. Otherwise the application starts as a web server.

### Agent Run lifecycle

`AgentRunExecutionService.execute()` orchestrates the run:

1. `saveSession(session)`
2. `saveRunStarted(run, engineType, prompt)`
3. `restoreHistory(session)` — load prior messages into working memory
4. snapshot `messagesBefore`
5. `engine.run(...)` — ReAct loop
6. `persistDeltaMessages(runId, messagesBefore)` — exactly once
7. `saveRunCompleted(...)` or `saveRunFailed(...)`
8. `persistUsageAggregate(...)` — only on successful engine completion

Usage persistence is a lifecycle action of the execution service, not a reporter responsibility.

### ReAct loop (`AgentEngine`)

- Optional thinking phase followed by an action phase.
- Tool calls are executed concurrently.
- Tool failures are recorded; repeated failures can fail the run.
- Supports subagent runs through `SubagentRunner`.

### Approval flow

`ApprovalGatePolicy` intercepts configured dangerous tools (`write_file`, `edit_file`, `shell_command`). `ApprovalResumeService` resumes or rejects a paused run via the REST API or CLI. Approval actions require the `ADMIN` role.

### Security

- `ApiKeyAuthFilter` reads the `X-API-Key` header.
- Admin key grants `ROLE_ADMIN`; user key grants `ROLE_USER`.
- `/api/v1/approvals/**` requires `ADMIN`.
- `/api/v1/**` requires authentication.
- `/actuator/**` is publicly accessible.
- Security can be disabled entirely via `tiny-claw.security.enabled=false`.
- CSRF is disabled for the stateless API.

### ChatOps (Feishu)

- Optional webhook endpoint: `POST /webhook/feishu/event`.
- Disabled by default (`tiny-claw.chatops.enabled=false`).
- The outbound `FeishuChatOpsMessageSender` makes real calls to the Feishu IM API when enabled and configured.
  - It obtains a cached `tenant_access_token` via `FeishuTenantAccessTokenProvider`, with refresh skew and thread-safe caching.
  - `FeishuMessageApiClient` posts text messages to `/open-apis/im/v1/messages?receive_id_type=chat_id`.
  - Token expiry errors (codes `99991661`, `99991663`, `99991664`, `99991668`) trigger a single retry after invalidating the cached token.
  - Success logs contain only `chatId`, `messageId`, `type`, and `textLength`; the full message body is never logged.
  - `ChatOpsProperties` validates `baseUrl` (must be HTTPS and hosted on `open.feishu.cn` or `open.larksuite.com`) and `requestTimeoutSeconds` (must be positive); invalid values fall back to safe defaults.

### Observability

- Custom Micrometer metrics under `tinyclaw.*`: `tinyclaw.runs.total`, `tinyclaw.tools.executed`, `tinyclaw.llm.latency`, `tinyclaw.context.size`.
- Health endpoints: `/actuator/health/llm`, `/actuator/health/db`.
- Prometheus endpoint: `/actuator/prometheus`.
- `MetricsConfiguration` filters out some JVM/system noise.

## Configuration

Spring Boot YAML files in `src/main/resources/`:

- `application.yml` — defaults; real LLM is disabled (`tiny-claw.model.enabled=false`), API-key security is enabled, and the approval gate is enabled.
- `application-test.yml` — H2 in-memory database (`MODE=PostgreSQL`), dummy LLM key, Flyway enabled.
- `application-dev.yml` — PostgreSQL on `localhost:5432/tinyclaw`.

Key property prefixes:

| Prefix | Purpose |
|---|---|
| `tiny-claw.model.*` | LLM provider, model, API key, base URL, timeout, retries, pricing |
| `tiny-claw.agent.*` | Max turns, max tool calls per turn, runtime seconds, plan mode |
| `tiny-claw.security.*` | API-key auth enable/disable, user key, admin key |
| `tiny-claw.tool.*` | Shell timeout, max output chars |
| `tiny-claw.workspace.root` | Default workspace root |
| `tiny-claw.approval.*` | Approval gate enable/disable, timeout, required tools |
| `tiny-claw.chatops.*` | Feishu webhook enable/disable, app id/secret, base URL, timeout, token refresh skew, allowlist |

Environment variables used:

- `LLM_API_KEY`, `LLM_BASE_URL`
- `TINyclaw_API_KEY`, `TINyclaw_ADMIN_KEY`
- `DB_URL`, `DB_USER`, `DB_PASSWORD`

## Database

Flyway migrations in `src/main/resources/db/migration/`:

- `V1__init_schema.sql`
- `V2__add_audit_fields.sql`
- `V3__create_approval_requests.sql`

Tables include `agent_sessions`, `agent_runs`, `agent_messages`, `tool_executions`, `approval_requests`, `usage_records`, `trace_spans`.

The schema is PostgreSQL-compatible; tests use H2 in PostgreSQL mode.

## Testing Strategy

- Framework: JUnit 5, Mockito, AssertJ, Spring Boot Test, MockMvc.
- Integration and repository tests use H2 in-memory with `@ActiveProfiles("test")`.
- Architecture boundaries are validated by `ArchitectureBoundaryTest`.
- Tests do **not** require a real LLM key; use `FakeLlmGateway` or `@MockBean LlmGateway`.
- Testcontainers PostgreSQL is available on the test classpath for containerized integration tests when needed.

Latest local verification (`mvn verify`):

| Metric | Value |
|---|---|
| Test classes | 98 |
| Tests run | 1009 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 13 |

JaCoCo coverage from `target/site/jacoco/index.html`:

| Metric | Value | Gate |
|---|---|---|
| Instructions | 90.03% | ≥ 90% |
| Branches | 76.93% | ≥ 75% |

The JaCoCo Maven plugin enforces the coverage gate during `mvn verify`. A standalone parser is available at `scripts/ci/check-coverage.ps1`.

## Code Style Guidelines

- New classes must have Javadoc (class-level and for public methods).
- Public method arguments must be validated with `DomainGuards.requireNonNull`, `requireNonBlank`, `requireNonNegative`, or `requirePositive`.
- Prefer immutability (`record` or final fields).
- Use constants or configuration injection; do not hardcode strings in business code.
- All exception paths must log (`log.warn` / `log.error`).
- Use SLF4J `LoggerFactory.getLogger(...)`.
- Maintain backward-compatible constructors; add new nullable parameters at the end.
- Respect hexagonal layer boundaries (see Architecture Rules).

## Security Considerations

- Real LLM calls are disabled by default; no API key is required to build or test.
- API keys must be provided via environment variables; never hardcode secrets.
- `ApiKeyAuthFilter` does not log key values.
- `ChatOpsSanitizer` masks secrets (API keys, tokens, passwords) in outbound ChatOps messages.
- Feishu `appSecret`, `tenant_access_token`, `Authorization` header, request body, and outbound message full text are never logged.
- Inbound message text is not written to production logs.
- `FeishuWebhookController` fails closed when `verifyToken` is missing.
- Error responses from `GlobalExceptionHandler` do not include stack traces.

## Deployment

- Packaging: Spring Boot executable fat jar.
- Artifact: `target/java-claw-0.0.1-SNAPSHOT.jar`.
- Start-Class: `com.tinyclaw.TinyClawApplication`.
- Main-Class (launcher): `org.springframework.boot.loader.launch.JarLauncher`.
- CI/CD: `.github/workflows/ci.yml` runs the P3 quality gate (Maven verify + coverage + P1 smoke) on push/PR.
- Deploy by running `java -jar target/java-claw-0.0.1-SNAPSHOT.jar` with the appropriate profile and environment variables.

## Design Documents

Additional context lives in `docs/`:

- `docs/phase4-strict-implementation-plan.md` — M4 productionization plan (Chinese).
- `docs/run-lifecycle-closure.zh-CN.md` — Agent Run lifecycle closure design (Chinese).
- `docs/superpowers/plans/2026-06-11-run-lifecycle-closure-fixes.md` — Implementation plan for lifecycle fixes (English).
- `docs/adr/0001-production-llm-path-openai-compatible.md` — ADR: near-term production LLM path is OpenAI-compatible via Spring AI.

## Repository Notes

- `benchmark-results.txt` — ignored JMH output; do not commit.
- `cp.txt` — ignored classpath dump, likely generated by an IDE; do not commit.

## Quick Reference for AI Agents

1. Run the full P3 gate after every meaningful change: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ci\verify.ps1`.
2. Or run individually: `mvn clean verify`, `scripts\ci\check-coverage.ps1`, `scripts\smoke\p1-e2e-smoke.ps1`, `git diff --check`.
3. Check architecture boundaries: `ArchitectureBoundaryTest` will fail if Spring/adapters/config leaks into `domain`, `ports`, or `application`.
4. Use `DomainGuards` for argument validation.
5. Add tests for new public methods; keep coverage above the JaCoCo thresholds (instruction ≥ 90%, branch ≥ 75%).
6. Do not require real LLM keys in tests; use `FakeLlmGateway` or `@MockBean LlmGateway`.
7. Use `--spring.profiles.active=test` and `--engine fake` for safe CLI smoke tests.
8. Do not commit real secrets; CI uses dummy keys and fails closed if coverage or smoke regresses.
