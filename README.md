# java-claw

Java implementation of go-tiny-claw agent harness.

## Requirements

- **JDK 21 or higher**
- Maven 3.9+
- (Optional) PostgreSQL 14+ for dev profile

## Verify Build Environment

Make sure Maven is using JDK 21+:

```bash
mvn -version
```

The output should show `Java version: 21.x.x` or higher.

If your default Maven uses an older JDK (e.g. JDK 17), set `JAVA_HOME` explicitly before running Maven:

```bash
# Git Bash / MSYS2
export JAVA_HOME="/c/Program Files/Java/jdk-21"
mvn clean verify

# PowerShell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
mvn clean verify
```

## Build

```bash
mvn clean verify
```

## Run CLI

```bash
java -jar target/java-claw-0.0.1-SNAPSHOT.jar run \
  --prompt "Hello agent" \
  --dir . \
  --session smoke \
  --spring.profiles.active=test \
  --spring.main.web-application-type=none
```

## Production LLM Provider

The near-term production path is **OpenAI-compatible via Spring AI** (`spring-ai-starter-model-openai`). `tiny-claw.model.base-url` can point to any OpenAI-compatible endpoint (DeepSeek, OpenAI, Azure OpenAI, local vLLM, etc.).

```bash
# Example: DeepSeek
export LLM_BASE_URL=https://api.deepseek.com
export LLM_API_KEY=your-key
java -jar target/java-claw-0.0.1-SNAPSHOT.jar \
  --tiny-claw.model.enabled=true \
  --tiny-claw.model.name=deepseek-v4-flash
```

See `docs/adr/0001-production-llm-path-openai-compatible.md` for the decision record and the conditions under which a native Anthropic/Claude adapter would be added.

## Quality Gate / CI

The project enforces a P3 quality gate on every push and pull request:

| Gate | Command | Threshold |
|---|---|---|
| Build + tests + coverage | `mvn clean verify` | instruction ≥ 90%, branch ≥ 75% |
| P1 smoke | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\p1-e2e-smoke.ps1` | CLI fake, Web run, approval pause/approve/resume, ChatOps webhook |
| Whitespace | `git diff --check` | no trailing whitespace / conflict markers |
| Full local gate | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ci\verify.ps1` | all of the above |

Coverage is enforced by the JaCoCo Maven plugin during `verify`. A standalone parser is also available at `scripts/ci/check-coverage.ps1`.

The GitHub Actions workflow (`.github/workflows/ci.yml`) runs:

1. `maven-verify` on Ubuntu with JDK 21 — `mvn -B -q clean verify`.
2. `p1-smoke` on Windows with JDK 21 — the same PowerShell smoke script used locally.

No real LLM key, Feishu key, or outbound Feishu call is required in CI. Dummy values are injected via environment variables; secrets are never logged.

On failure, CI uploads:

- `target/surefire-reports/`
- `target/site/jacoco/`
- `.smoke/logs/`
- `notes/session-logs/`

## Run Tests

```bash
mvn test
```

Tests do **not** require any real LLM API key.
