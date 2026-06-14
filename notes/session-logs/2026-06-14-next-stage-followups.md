# 2026-06-14 Next Stage Follow-ups

## Context

After the Feishu real message sending implementation review, the current codebase is considered ready to move forward with no P0/P1 blockers found in the review gate. The following items must stay visible for the next development pass.

## Follow-up Tasks

### P1 - End-to-end smoke verification

Run one complete smoke pass across the main runtime paths:

- CLI fake run.
- Web run.
- Approval pause -> approve -> resume.
- ChatOps webhook.

Success checks:

- CLI fake run completes without a real LLM key.
- Web API can start a run and return/observe the expected run state.
- Approval-required operation pauses, can be approved, and resumes successfully.
- Feishu ChatOps webhook accepts a valid event and triggers the expected ChatOps flow without leaking message body or secrets in logs.

### P2 - Production LLM gateway decision

Decide whether to add an Anthropic/Claude `LlmGateway` implementation, or explicitly document that Spring AI OpenAI-compatible integration is the only near-term production path.

Success checks:

- If Anthropic/Claude is needed, define and implement the adapter behind the existing `LlmGateway` port with tests.
- If not needed, document the decision, supported provider path, configuration expectations, and any known limitations.
- No provider-specific dependency may leak into domain, ports, or application layers.

### P2 - Worktree hygiene and version-control cleanup

Clean up the current working tree and classify generated/untracked files.

Items to review:

- `AGENTS.md`
- `docs/`
- V4 migration files
- domain/tool changes
- `cp.txt`
- `benchmark-results.txt`

Success checks:

- Files that are project documentation, migrations, source, or tests are intentionally tracked.
- Generated or local-only artifacts such as classpath dumps and benchmark output are ignored, deleted, or moved according to project policy.
- The resulting `git status` is understandable and ready for commit/PR preparation.
