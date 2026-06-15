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

## Run Tests

```bash
mvn test
```

Tests do **not** require any real LLM API key.
