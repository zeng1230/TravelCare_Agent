# Stage 7B Acceptance

## Required behavior

- A complete new trace passes readiness and creates a separate `dryRun=true` trace.
- A legacy trace missing required snapshots is rejected without creating a trace or diff.
- Dry run does not add rows to sessions, session events, workflows, workflow steps, tool calls, idempotency keys, audit logs, human review cases, workflow tasks, refund cases, or agent runs.
- Dry run does not publish RabbitMQ messages, call DeepSeek, or invoke `MockOrderAdapter`.
- Tool and retrieval execution uses snapshots; model execution uses the mock provider.
- Repeated execution with the same snapshots produces the same final output.
- Diff results include changed fields, normalized summaries, explanation, and risk level.
- Evaluation reports are written per suite and aggregated without last-suite overwrite.

## APIs

```http
POST /api/agent-traces/{traceId}/dry-run
GET /api/agent-traces/{traceId}/diffs/{dryRunTraceId}
```

Readiness rejection:

```json
{
  "code": "DRY_RUN_NOT_READY",
  "data": {
    "status": "REJECTED",
    "dryRunTraceId": null,
    "diffId": null,
    "missingSnapshots": ["TOOL_RESULT"],
    "allowedActions": ["VIEW_TRACE", "VIEW_DIAGNOSTICS"]
  }
}
```

## Boundaries

- Replay remains read-only historical inspection.
- Trace remains a diagnostic view and does not replace business facts.
- Dry run is a side-effect-free diagnostic simulation based only on structured sanitized snapshots.
- Diff explains structural changes between the original and simulated trace.
- No real refund, payment, supplier, or DeepSeek execution is performed.
- Local acceptance uses the built-in mock order `ORD-1001`. There is no `/api/orders/mock` endpoint and no `travel_orders` table.
