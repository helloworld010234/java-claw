# ADR 0001: Production LLM Path — OpenAI-compatible via Spring AI

## Status

Accepted — P2 convergence, 2026-06-15.

## Context

`java-claw` needs a single, supportable production path to a Large Language Model (LLM) for its ReAct agent engine. The engine consumes the provider-agnostic `LlmGateway` port (`com.tinyclaw.ports.llm.LlmGateway`), so any provider must be hidden behind that port.

At the start of P2 we had:

- `FakeLlmGateway` for tests and offline CLI smoke.
- `SpringAiLlmGateway` backed by Spring AI's `OpenAiChatModel` / `OpenAiApi`.
- No Anthropic/Claude native adapter.
- No separate provider-selection mechanism beyond the `tiny-claw.model.provider` property, which currently only supports `spring-ai`.

The question under review was:

> Should we implement a native Anthropic/Claude `LlmGateway`, or is the Spring AI OpenAI-compatible adapter sufficient for near-term production?

## Decision

**The near-term production path is a single OpenAI-compatible HTTP endpoint accessed through Spring AI's `spring-ai-starter-model-openai`.** No native Anthropic/Claude adapter will be added in P2.

## Rationale

1. **Protocol compatibility covers the target provider.**  
   The current target model (`deepseek-v4-flash`) and many other providers (OpenAI, Azure OpenAI, local vLLM/Ollama with an OpenAI-compatible front-end, several Chinese cloud models) expose the OpenAI chat-completions API shape. Spring AI's `OpenAiChatModel` is therefore a generic OpenAI-protocol client, not an OpenAI-only client.

2. **Configuration is already sufficient.**  
   `tiny-claw.model.base-url` can point to any OpenAI-compatible endpoint, and `tiny-claw.model.name` selects the model. The existing timeout, retry, and usage-cost wrappers are provider-agnostic.

3. **P1 smoke validation passed.**  
   The P1 end-to-end smoke used an OpenAI-compatible HTTP stub to exercise CLI fake run, web run, approval pause/approve/resume, and ChatOps webhook. The Spring AI adapter handled tool calls, usage metadata, and streaming-style response parsing correctly against the stub.

4. **Scope discipline.**  
   Adding a native Anthropic adapter would introduce a new dependency (`spring-ai-anthropic` or a hand-written HTTP client), new request/response mappers, new error-classification paths, and new tests. That is justified only when we have a concrete requirement that the OpenAI-compatible path cannot satisfy.

5. **Architecture integrity.**  
   Keeping one adapter behind `LlmGateway` avoids provider detail leaking into `domain`, `ports`, or `application`. The port remains clean and the engine remains provider-agnostic.

## Configuration Reference

```yaml
tiny-claw:
  model:
    enabled: false               # set true in production
    provider: spring-ai          # fixed near-term value
    name: deepseek-v4-flash      # or gpt-4o, claude via OpenAI-compatible proxy, etc.
    base-url: ${LLM_BASE_URL:}   # e.g. https://api.deepseek.com
    api-key: ${LLM_API_KEY:}     # provider API key
    temperature: 0.7
    max-tokens: 4096
    pricing:
      input-price-per-1m: 0.0
      output-price-per-1m: 0.0
    request-timeout-seconds: 60
    max-retry-attempts: 3
    retry-backoff-ms: 1000
    usage-cost-summary-enabled: true
```

Environment variables:

- `LLM_API_KEY`
- `LLM_BASE_URL`
- `TINyclaw_API_KEY` / `TINyclaw_ADMIN_KEY` (for service auth, not the LLM provider)

## Known Limitations

- **Provider-specific features are unavailable.**  Any feature that requires a native SDK or non-OpenAI API shape (e.g. Anthropic's `thinking` blocks, prompt caching hints, computer-use beta headers) cannot be used.
- **Tool-call schema is OpenAI-shaped.**  Providers that deviate from the OpenAI tool-calling JSON schema may fail or require a proxy.
- **Usage metadata depends on the endpoint.**  Some OpenAI-compatible proxies return zero usage; cost estimation falls back to zero in that case.

## Future Trigger for a Native Anthropic/Claude Adapter

Add `adapters/llm/anthropic/AnthropicLlmGateway` (or Spring AI's `spring-ai-anthropic` adapter) only when at least one of the following becomes true:

1. A required production model is Claude and its OpenAI-compatible endpoint is missing, deprecated, or unsupported by the operations team.
2. We need Claude-specific capabilities (extended thinking, computer use, prompt caching, system prompt caching) that materially improve agent quality and cannot be emulated through the OpenAI path.
3. The OpenAI-compatible proxy introduces reliability or cost issues that a native adapter would resolve.

If triggered, the new adapter must:

- Implement `LlmGateway` only.
- Live under `adapters/llm/anthropic`.
- Re-use the existing `TimeoutLlmGateway`, `RetryingLlmGateway`, and `ObservedLlmGateway` wrappers.
- Add `LlmErrorClassifier` cases without changing `ports/llm/LlmErrorType` semantics.
- Be selected by `tiny-claw.model.provider=anthropic` with explicit configuration validation.

## Consequences

- **Positive:** Smaller codebase, one production dependency to maintain, clear operational runbook, P1 smoke remains valid.
- **Positive:** No Spring/adapters leak into `domain`, `ports`, or `application`.
- **Negative:** Claude-specific features are not available out-of-the-box; users must use an OpenAI-compatible endpoint for Claude or wait for the trigger conditions above.

## Related Files

- `src/main/java/com/tinyclaw/ports/llm/LlmGateway.java`
- `src/main/java/com/tinyclaw/adapters/llm/springai/SpringAiLlmGateway.java`
- `src/main/java/com/tinyclaw/config/TinyClawModelConfiguration.java`
- `src/main/java/com/tinyclaw/config/TinyClawModelProperties.java`
- `src/main/resources/application.yml`
