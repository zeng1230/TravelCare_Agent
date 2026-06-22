# Stage 10B: Prompt Version and Agent Run Tracking

## Goal

Stage 10B makes every `AgentModelService` invocation diagnosable as one logical
`agent_runs` record. Provider attempts remain trace spans; no attempt table is
introduced. Agent runs are diagnostic evidence only and are not workflow,
refund, payment, policy, audit, or eligibility facts.

## Logical Run Lifecycle

One model-service invocation inserts one `RUNNING` row and updates that same row
to one terminal status:

- `SUCCESS`: the configured primary provider returned a valid response.
- `FAILED`: request preparation failed before a provider result was available.
- `FALLBACK_SUCCESS`: the primary attempt failed and deterministic mock fallback
  returned a valid response.
- `FALLBACK_FAILED`: both attempts failed.

Historical `SUCCEEDED` and `FAILED_*` values remain valid for pre-Stage-10B
business-level agent runs. Primary and fallback attempt timing and errors remain
available through MODEL and FALLBACK trace spans.

Agent-run persistence is best effort. A database write failure is logged without
payload data and must not replace a successful provider result or the original
`ModelCallException`.

## Recorded Evidence

Model-call rows record:

- nullable session and workflow IDs;
- operation in the existing `run_type` column;
- configured provider mode, primary provider/model, and optional fallback
  provider/model;
- the prompt template version actually rendered;
- input event IDs and retrieval context IDs, where retrieval context IDs are
  knowledge chunk IDs;
- SHA-256 request and validated-response hashes;
- provider-reported input, output, and total token usage without estimation;
- total logical-call latency, fallback usage, status, safe error code, trace/span
  IDs, and timestamps.

`request_hash` is calculated from canonical operation, model, prompt version,
and invocation input. `response_hash` is calculated from canonical validated
JSON. Hash inputs are transient and are not persisted.

## Data Protection

V11 removes `request_json` and `response_json` from `agent_runs`. Model-call
records and their APIs do not contain complete prompts, user text, provider
requests or responses, API keys, authorization headers, HTTP error bodies, RAG
chunk text, or memory text.

`error_message` is exposed as `errorMessageSanitized` and accepts only these
whitelisted forms:

```text
Primary model attempt failed: <ERROR_CODE>
Primary model attempt failed: <ERROR_CODE>; fallback model attempt failed: <ERROR_CODE>
```

Raw exception messages and exception chains are never copied into model-call
agent runs.

## Evaluation

Evaluation remains restricted to `provider_mode=mock` and performs no real
provider network calls. The existing `prompt_stub_version` remains the stored
evaluation field; generated reports present it as `Prompt version` alongside
`Provider mode`. Stage 8 baseline comparison and Stage 9 answerability/citation
scoring behavior are unchanged.

## Replay and Limitations

Agent-run detail and replay responses expose IDs, hashes, versions, provider
diagnostics, token usage, and existing referenced evidence. They cannot recreate
the exact provider request or response. This stage does not add providers,
retries, circuit breakers, an observability platform, LangGraph, workflow or
refund-policy changes, real payments/refunds/supplier APIs, or the complete
Stage 10C safety gate.
