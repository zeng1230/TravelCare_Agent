# Stage 7B Diagnostic Dry Run & Diff

## Goal

Stage 7B adds a diagnostic dry-run path on top of Stage 7A traces. It re-evaluates captured structured inputs without writing business facts, publishing RabbitMQ messages, invoking DeepSeek, or calling order adapters.

## Snapshot contract

New production traces persist these sanitized structured snapshot types before they are considered dry-run ready:

- `USER_INPUT`
- `CONTEXT_SUMMARY`
- `RETRIEVAL_SUMMARY`
- `MODEL_INPUT`
- `MODEL_OUTPUT`
- `TOOL_REQUEST`
- `TOOL_RESULT`
- `POLICY_INPUT`
- `POLICY_DECISION`
- `WORKFLOW_PATH`
- `FINAL_OUTPUT`

Policy and tool snapshots contain structured order status, refundable flag, amount, departure time, user identity, and `evaluatedAt`. Dry run never reconstructs these facts from natural-language text.

## Readiness

`DryRunReadinessChecker` runs before creating a dry-run trace. A legacy or incomplete trace returns `DRY_RUN_NOT_READY`, `status=REJECTED`, no `dryRunTraceId`, no diff, and only `VIEW_TRACE` and `VIEW_DIAGNOSTICS`.

Readiness rejection is not an execution failure and writes no trace or diff records.

## Execution model

- `SnapshotRetrievalExecutor` reuses the captured retrieval summary.
- `SnapshotToolExecutor` reuses the captured tool result and does not call `ToolService` or `OrderAdapter`.
- `RefundEligibilityPolicy.evaluateAt` re-evaluates policy with captured `evaluatedAt`.
- `DryRunWorkflowSimulator` creates an in-memory workflow path without business writes.
- `DryRunModelExecutor` invokes only `MockAgentProvider`.
- `SideEffectGuard` is a final safety net. If triggered, it records `DRY_RUN_SKIPPED_SIDE_EFFECT` and the run fails with high risk.

Dry run may write only Stage 7 trace tables and `agent_trace_diffs`.

## Diff

`TraceDiffService` compares final output, workflow path, tool result, policy decision, retrieval summary, model output, special events, errors, and span status distribution. Risk levels are `NONE`, `LOW`, `MEDIUM`, and `HIGH`.

This is Diagnostic Dry Run Lite, not production-grade deterministic replay.
