# Stage 10A: LLM Provider Abstraction

## Goal

Stage 10A replaces the legacy `AgentProvider` model-call entry point with one
`ChatModelProvider` abstraction. The application can select a deterministic
mock provider or a DeepSeek-compatible chat-completions provider through
configuration.

This stage covers provider contracts, provider selection, timeout handling,
safe fallback behavior, tests, and basic operating documentation. It does not
implement the complete Stage 10 structured-output safety gate or deep agent-run
replay.

## Architecture

`ChatModelProvider` accepts a provider-neutral `ModelRequest` made of chat
messages, model settings, prompt version, timeout, and controlled metadata. It
returns a `ModelResponse` containing content, provider/model identity, token
usage, latency, and finish reason.

`AgentModelService` remains the boundary between business operations and model
calls. It renders existing prompt templates, maps the controlled operation and
input into request metadata, invokes the selected provider, validates the
operation-specific JSON response, and records model-call diagnostics. The
provider does not execute workflows, tools, refunds, payments, cancellations,
or supplier actions.

The legacy `AgentProvider`, request/response records, implementations, and tests
are removed. No compatibility adapter is retained, so all model calls have one
entry point for later Stage 10B and Stage 10C work.

## Providers

### Mock

`MockChatModelProvider` is the default. It never performs network I/O and uses
only controlled metadata assembled by `AgentModelService` to reproduce the
existing deterministic intent classification and response-generation behavior.
This keeps local development, Maven tests, dry runs, and evaluation behavior
independent of credentials and external services.

### DeepSeek

`DeepSeekChatModelProvider` uses Spring `RestClient` and the DeepSeek-compatible
`POST /chat/completions` API. It is selected only when
`travelcare.agent.provider=deepseek` is configured explicitly.

Missing credentials, connection/read timeouts, HTTP failures, malformed
responses, and empty content are converted to `ModelCallException`. Error text
and logs must not contain API keys, authorization headers, or response secrets.

## Configuration

Default configuration:

```properties
travelcare.agent.provider=mock
travelcare.agent.model=mock-stage10a
travelcare.agent.prompt-version=stage10a-default
travelcare.agent.timeout-ms=5000
```

Manual DeepSeek configuration:

```properties
travelcare.agent.provider=deepseek
travelcare.agent.model=deepseek-chat
travelcare.agent.api-key=${TRAVELCARE_AGENT_API_KEY}
travelcare.agent.base-url=https://api.deepseek.com
travelcare.agent.timeout-ms=8000
```

The API key must come from an environment variable or an external configuration
source. A real key must never be committed.

PowerShell example:

```powershell
$env:TRAVELCARE_AGENT_API_KEY = '<your-api-key>'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--travelcare.agent.provider=deepseek --travelcare.agent.model=deepseek-chat --travelcare.agent.api-key=$env:TRAVELCARE_AGENT_API_KEY --travelcare.agent.base-url=https://api.deepseek.com --travelcare.agent.timeout-ms=8000"
```

Manual smoke tests are intentionally separate from `mvn test`. Automated tests
must use the mock provider or a local fake HTTP server and must not contact an
external API.

## Failure and Fallback Behavior

`AgentModelService` catches `ModelCallException`, invalid JSON, missing required
fields, and empty operation results from the selected provider. It then invokes
`MockChatModelProvider` with the same controlled operation metadata.

The fallback is deterministic and limited to intent classification or customer
reply drafting. It cannot initiate side effects. Existing workflow, refund
policy, tool-call, authorization, persistence, audit, RAG, trace, and evaluation
boundaries remain authoritative.

If the mock fallback itself fails, the service raises a safe internal error
without exposing provider secrets. Provider failures may be recorded in the
existing model-run/trace diagnostics, but they must not create or advance
session events, workflows, workflow steps, tool calls, audit logs, or evaluation
reports.

## Test Strategy

Tests cover default mock selection, deterministic mock output, missing-key
errors for an explicitly selected DeepSeek provider, local fake-server HTTP
mapping, timeout/error conversion, invalid or empty response fallback, and
existing evaluation and refund workflow regressions. The complete Maven suite
must pass without a real API key or external network access.

## Known Limitations

- Provider output is still operation-specific JSON interpreted by
  `AgentModelService`; a complete typed structured-output safety gate is deferred
  to Stage 10B/10C.
- Retry, circuit-breaker, rate-limit, and multi-provider failover policies are
  not introduced in Stage 10A.
- Deep agent-run replay and provider-response replay are out of scope.
- The real provider is intended for manual smoke testing only until later safety
  stages are complete.

