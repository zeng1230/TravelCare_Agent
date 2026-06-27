# PR-1B Async Reliability Boundary

## Background

TravelCare-Agent has completed RC acceptance and PR-1A Security Boundary. PR-1B hardens the existing asynchronous workflow path without renaming it Stage 11 and without adding new AI product capability.

The implementation keeps the current monolith, workflow engine, Spring Security boundary, and mock-provider test contract.

## Goals

- Prevent silent loss of workflow task dispatches.
- Make publisher confirm success and failure visible in durable state.
- Bound worker retries and avoid RabbitMQ infinite requeue behavior.
- Provide RabbitMQ DLQ for poison messages and sanitized dead-letter events.
- Make timeout UNKNOWN explicit only where retrying may duplicate external side effects.
- Add a minimal reconciliation path for UNKNOWN side-effecting calls.

## Non-Goals

- No Spring AI, LangGraph, MCP Tool Store, multi-agent platform, admin backend, Kubernetes, or microservice split.
- No real DeepSeek, real LLM provider, real supplier, real payment, or real refund connection.
- No full OpenTelemetry platform; PR-1C can build on the fields added here.
- No new public API in PR-1B.

## Queue Design

- Main exchange: `workflow.exchange`
- Main queue: `workflow.tasks.reliable.queue`
- Main routing key: `workflow.tasks.routing.key`
- Dead-letter exchange: `workflow.dlx`
- Dead-letter queue: `workflow.tasks.dlq`
- Dead-letter routing key: `workflow.tasks.dlq.routing.key`

RabbitMQ DLQ is a broker fallback for poison messages, malformed payloads, missing task IDs, and unrecoverable consumer-message failures. Business retry is not implemented through RabbitMQ automatic requeue.

## Outbox Design

`outbox_events` is the durable delivery boundary. It stores event type, aggregate identity, routing key, JSON payload, `payload_version`, `dedupe_key`, status, attempts, retry timing, trace ID, and publish timestamp.

Statuses: `NEW`, `PUBLISHING`, `PUBLISHED`, `RETRYING`, `FAILED`.

Payloads are versioned. PR-1B implements only `payload_version = v1`; later phases can add parsers for new versions keyed by `event_type + payload_version`.

Creation is deduplicated with `dedupe_key`:

- Workflow retry: `workflow_task:{taskId}:attempt:{attempts}`
- Dead letter: `workflow_task:{taskId}:dead-letter:{attempts}`

Duplicate creation reuses the existing outbox event.

## Publisher Confirm Design

`OutboxPublisher` scans due `NEW` and `RETRYING` events. Before sending, it uses a compare-and-set claim from `NEW/RETRYING` to `PUBLISHING`; only the winning publisher sends.

Publisher results:

- Confirm ack -> `PUBLISHED`, with `published_at`.
- Nack, timeout, or send exception -> `RETRYING` with `next_retry_at`, or `FAILED` after max publish attempts.
- Failed sends never delete the outbox row.

Stale recovery prevents permanent `PUBLISHING` lock-in. If an app crashes after claiming an event, `PUBLISHING` older than the configured threshold is recovered to `RETRYING`.

PR-1B does not require the RabbitMQ delayed-message plugin. Delay is implemented through database `next_retry_at`; RabbitMQ only receives due messages.

## Worker Retry / DLQ Semantics

`workflow_tasks` remains the business retry source of truth.

On worker failure:

- The current RabbitMQ message is acked by returning normally.
- The worker increments task attempts and records error code/message.
- If attempts are below max, it sets `next_run_at` and creates the next dispatch outbox event in the same transaction.
- If attempts reach max, it marks the task `FAILED` and creates a dead-letter outbox event in the same transaction.

The sanitized `DeadLetterMessage` DTO allows only `taskId`, `workflowId`, `toolCallId`, `traceId`, `failureCode`, `attempts`, `deadLetterReason`, `outboxEventId`, and `createdAt`.

It must not contain request JSON, response JSON, prompts, provider raw errors, tokens, or secrets.

## Timeout UNKNOWN Semantics

UNKNOWN is only for external calls that may have produced side effects before timeout, such as supplier booking, refund, payment, cancellation, reschedule, or notification.

UNKNOWN is not the default for LLM timeout, pure retrieval timeout, or read-only tool timeout, including `GetOrderTool`. Those paths use timeout failure, fallback, or normal failed status.

The reason is that retrying a side-effecting call after timeout can duplicate the external action, while retrying or failing a read-only call does not create the same external consistency risk.

## Reconciliation Design

`reconciliation_jobs` stores pending reconciliation for UNKNOWN side-effecting calls. It has a unique source boundary: `source_type + source_id`.

Duplicate pending creation for the same source reuses the existing record. A mock reconciliation service can resolve a pending job to `CONFIRMED_SUCCESS`, `CONFIRMED_FAILED`, or `UNKNOWN`.

If reconciliation cannot decide, the source remains UNKNOWN and the reconciliation job remains pending or inconclusive.

## Idempotency Strategy

- Worker re-reads task state before side effects.
- Terminal task states are skipped and record a skipped reason.
- Outbox event creation uses dedupe keys.
- Outbox publish uses CAS claim before broker send.
- Reconciliation creation uses source uniqueness.

## Security Boundary

PR-1B adds no public API. Internal worker execution does not depend on USER tokens or development tokens. Spring Security remains enabled, and PR-1A 401/403 regression tests must continue to pass.

## Test Checklist

- Outbox confirm ack marks `PUBLISHED`.
- Nack, timeout, and send exception leave the event visible and retryable.
- Max publish attempts marks `FAILED`.
- Stale `PUBLISHING` is recovered.
- Concurrent publisher claim lets only one sender publish.
- Worker failure below max creates retry state and next outbox event.
- Worker failure at max creates `FAILED` state and sanitized dead-letter event.
- Duplicate terminal task messages are skipped.
- Side-effecting timeout creates UNKNOWN and one reconciliation job.
- Read-only timeout does not default to UNKNOWN.
- `mvn test` remains fixed to `travelcare.agent.provider=mock`.

## PR-1C Handoff

PR-1C can add metrics, dashboards, alerts, OpenTelemetry spans, DLQ inspection workflows, and operational reconciliation views using the durable fields introduced here: trace ID, job/task ID, outbox event ID, workflow ID, tool call ID, attempts, status, last error code, retry time, publish time, dead-letter reason, and skipped reason.
