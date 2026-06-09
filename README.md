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

## Run Tests

```bash
mvn test
```

Tests do **not** require any real LLM API key.
