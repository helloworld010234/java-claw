# Agent Run Lifecycle Closure Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix code review blockers in java-claw: eliminate duplicate message persistence in AgentRunExecutionService, make usage persistence a real lifecycle closure, fix CompositeReporter silent swallowing, enhance show-run, and add comprehensive tests.

**Architecture:** Refactor AgentRunExecutionService.execute() into clear lifecycle phases with single-path message persistence. Move usage persistence from Reporter chain into AgentRunExecutionService as a critical post-run action. CompositeReporter logs warnings on reporter failures. show-run shows usage summary.

**Tech Stack:** Java 21, Spring Boot 3.5, H2 (test), JUnit 5, AssertJ

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `AgentRunExecutionService.java` | Modify | Core lifecycle orchestration: single persistDeltaMessages, usage aggregate persistence |
| `CompositeReporter.java` | Modify | Log warnings instead of silently swallowing exceptions |
| `UsagePersistingReporter.java` | Delete (or deprecate) | No longer the critical path for usage persistence |
| `ShowRunCommand.java` | Modify | Add usage summary to default output; stable no-usage output |
| `AgentRunExecutionServiceTest.java` | Modify/Expand | Test single persistence, exception paths, no duplication |
| `AgentRunExecutionServiceFailureTest.java` | Modify/Expand | Test failure path messages preserved, usage not written on LLM failure |
| `AgentRunExecutionServiceResumeTest.java` | Modify/Expand | Test second run on same session hydrates history, persists only new messages |
| `CompositeReporterTest.java` | Modify | Verify failure logging, non-critical reporter failure doesn't block others |
| `ShowRunCommandTest.java` | Modify/Expand | Test usage summary output, detail usage output, no-usage stable output |
| `JdbcUsageRepositoryTest.java` | Create | H2 integration test: valid FK usage_records insert after session/run exist |
| `run-lifecycle-closure.zh-CN.md` | Create | Phase design document |

---

## Task 1: Fix AgentRunExecutionService Message Persistence

**Files:**
- Modify: `src/main/java/com/tinyclaw/application/run/AgentRunExecutionService.java`

- [ ] **Step 1: Remove catch/finally duplicate persistDeltaMessages**

Current code (lines 76-87):
```java
AgentRunResult result;
try {
    result = engine.run(run, session, prompt, context, toolExecutionRepository);
} catch (Exception e) {
    persistDeltaMessages(runId, session, messagesBefore);
    if (runRepository != null) {
        runRepository.saveRunFailed(runId, run.currentTurn(), e.getMessage(), Instant.now());
    }
    throw e;
} finally {
    persistDeltaMessages(runId, session, messagesBefore);
}
```

Replace with single-path persistence:
```java
AgentRunResult result;
try {
    result = engine.run(run, session, prompt, context, toolExecutionRepository);
} catch (Exception e) {
    if (runRepository != null) {
        runRepository.saveRunFailed(runId, run.currentTurn(), e.getMessage(), Instant.now());
    }
    throw e;
} finally {
    persistDeltaMessages(runId, session, messagesBefore);
}
```

Rationale: `finally` always executes. If engine throws, `finally` runs once. If engine returns, `finally` runs once. No duplication.

- [ ] **Step 2: Verify persistDeltaMessages is safe for empty delta**

Current `persistDeltaMessages` skips empty lists naturally via the stream skip/filter. Confirm no change needed.

- [ ] **Step 3: Run existing tests**

```bash
mvn test -Dtest=AgentRunExecutionServiceTest,AgentRunExecutionServiceFailureTest,AgentRunExecutionServiceResumeTest,RunCommandAuditTest
```

Expected: ALL PASS

---

## Task 2: Move Usage Persistence into AgentRunExecutionService

**Files:**
- Modify: `src/main/java/com/tinyclaw/application/run/AgentRunExecutionService.java`
- Modify: `src/test/java/com/tinyclaw/application/run/AgentRunExecutionServiceTest.java`

- [ ] **Step 1: Add UsageRepositoryPort dependency to AgentRunExecutionService**

Add constructor parameter (optional/nullable to avoid breaking all existing call sites):
```java
private final UsageRepositoryPort usageRepository;

public AgentRunExecutionService(RunRepositoryPort runRepository,
                                 MessageRepositoryPort messageRepository,
                                 SessionService sessionService,
                                 ObjectMapper objectMapper,
                                 Reporter reporter,
                                 UsageRepositoryPort usageRepository) {
    // ... existing assignments ...
    this.usageRepository = usageRepository;
}
```

- [ ] **Step 2: Add usage aggregate persistence after run completion/failure**

In `execute()`, after `saveRunCompleted` / `saveRunFailed`:
```java
if (usageRepository != null && result != null && result.totalUsage() != null) {
    persistUsageAggregate(runId, session.id(), result.totalUsage(), engineType);
}
```

For the catch path (exception thrown, result is null), usage is not written because engine didn't complete.

Add private method:
```java
private void persistUsageAggregate(String runId, String sessionId, Usage usage, String engineType) {
    double cost = 0.0; // simplified; pricing can be added later
    UsageRecord record = new UsageRecord(
        runId,
        sessionId,
        engineType != null ? engineType : "unknown",
        usage.promptTokens(),
        usage.completionTokens(),
        cost > 0 ? cost : null,
        true,
        Instant.now()
    );
    try {
        usageRepository.save(record);
    } catch (Exception e) {
        // Log but do not silently swallow; the run itself succeeded
        // In a stricter mode this could fail the run, but for now we log warning
        log.warn("Failed to persist usage aggregate for run {}: {}", runId, e.getMessage());
    }
}
```

- [ ] **Step 3: Update tests to verify usage persistence**

In `AgentRunExecutionServiceTest`, add `InMemoryUsageRepository` and verify that successful run writes usage when engine returns totalUsage.

---

## Task 3: Fix CompositeReporter Silent Exception Swallowing

**Files:**
- Modify: `src/main/java/com/tinyclaw/adapters/reporter/CompositeReporter.java`
- Modify: `src/test/java/com/tinyclaw/adapters/reporter/CompositeReporterTest.java`

- [ ] **Step 1: Add SLF4J logger and warn on reporter failure**

```java
private static final Logger log = LoggerFactory.getLogger(CompositeReporter.class);
```

Change each method from:
```java
try {
    r.onXxx(...);
} catch (Exception ignored) { }
```

To:
```java
try {
    r.onXxx(...);
} catch (Exception e) {
    log.warn("Reporter {} failed on {}: {}", r.getClass().getSimpleName(), "onXxx", e.getMessage());
}
```

- [ ] **Step 2: Update CompositeReporterTest**

Add test verifying warn log output (use `@CaptureSystemOutput` or verify through a custom logger appender, or simply verify that the method completes without propagating the exception and other reporters still receive events).

Keep existing `singleReporterFailureDoesNotAffectOthers` but update assertion to verify failure is observable (e.g., check stderr contains warning, or add a test that verifies log output).

---

## Task 4: Deprecate/Remove UsagePersistingReporter from Critical Path

**Files:**
- Modify: `src/main/java/com/tinyclaw/adapters/reporter/UsagePersistingReporter.java` (mark as deprecated)
- Modify: `src/test/java/com/tinyclaw/adapters/reporter/UsagePersistingReporterTest.java` (keep but document deprecated)

- [ ] **Step 1: Mark UsagePersistingReporter as deprecated**

Add `@Deprecated` annotation and javadoc explaining usage persistence is now handled by AgentRunExecutionService.

- [ ] **Step 2: Verify no Spring configuration still wires it as critical path**

Search for `UsagePersistingReporter` in config files. If found in `RunConfiguration` or similar, remove or make it non-critical.

---

## Task 5: Enhance ShowRunCommand

**Files:**
- Modify: `src/main/java/com/tinyclaw/adapters/cli/ShowRunCommand.java`
- Modify: `src/test/java/com/tinyclaw/adapters/cli/ShowRunCommandTest.java`

- [ ] **Step 1: Add usage summary to default output**

In `call()`, after `error` line:
```java
int totalPromptTokens = 0;
int totalCompletionTokens = 0;
if (usageRepository != null) {
    List<UsageRecord> usageList = usageRepository.findByRunId(runId);
    for (UsageRecord u : usageList) {
        totalPromptTokens += u.promptTokens();
        totalCompletionTokens += u.completionTokens();
    }
}
if (totalPromptTokens > 0 || totalCompletionTokens > 0) {
    System.out.println("usage: " + totalPromptTokens + " prompt / " + totalCompletionTokens + " completion tokens");
} else {
    System.out.println("usage: none");
}
```

- [ ] **Step 2: Update ShowRunCommandTest**

Add tests:
- `showRunWithUsageOutputsSummary()` - insert usage record, verify output contains usage totals
- `showRunWithoutUsageOutputsNone()` - verify output contains "usage: none"
- `showRunDetailWithUsageOutputsRecords()` - verify --detail shows usage records

---

## Task 6: Create JdbcUsageRepository H2 Integration Test

**Files:**
- Create: `src/test/java/com/tinyclaw/adapters/persistence/JdbcUsageRepositoryTest.java`

- [ ] **Step 1: Write test that proves FK validity**

```java
@SpringBootTest
@ActiveProfiles("test")
class JdbcUsageRepositoryTest {
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JdbcRunRepository runRepository;
    @Autowired JdbcUsageRepository usageRepository;

    @Test
    void usageRecordRequiresValidRunAndSession() {
        Session session = Session.create("usage-sess", "/tmp", Instant.now());
        AgentRun run = AgentRun.start("usage-run", "usage-sess", 3, Instant.now());
        runRepository.saveSession(session);
        runRepository.saveRunStarted(run, "fake", "test");

        UsageRecord record = new UsageRecord("usage-run", "usage-sess", "fake", 100, 50, null, true, Instant.now());
        usageRepository.save(record);

        List<UsageRecord> found = usageRepository.findByRunId("usage-run");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).promptTokens()).isEqualTo(100);
    }
}
```

---

## Task 7: Write Phase Design Document

**Files:**
- Create: `D:/go-tiny-claw/java-claw/docs/run-lifecycle-closure.zh-CN.md`

Content must include:
- Go baseline summary
- Java current implementation snapshot
- This phase's fix targets
- run/message/tool/usage lifecycle sequence diagram (text)
- Usage persistence strategy rationale
- Reporter failure strategy
- Explicit non-goals (Feishu ChatOps, benchmark, subagent, full trace spans)
- Next phase recommendations

---

## Task 8: Full Verification

- [ ] **Step 1: mvn clean verify**

```bash
mvn clean verify
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 2: CLI smoke tests**

```bash
java -jar target/java-claw-0.0.1-SNAPSHOT.jar run --prompt "hello" --dir . --session smoke-fake --engine fake --spring.profiles.active=test --spring.main.web-application-type=none
java -jar target/java-claw-0.0.1-SNAPSHOT.jar show --run-id <run-id-from-above>
```

- [ ] **Step 3: Git delta check**

```bash
git status --short --untracked-files=all -- java-claw
git diff --stat -- java-claw
```

Ensure only intended files are changed.

- [ ] **Step 4: Security check**

Search for real API keys in src/:
```bash
grep -r "sk-" src/ || true
grep -r "api.deepseek.com" src/ || true
```

Expected: no matches in tests (only placeholders).
