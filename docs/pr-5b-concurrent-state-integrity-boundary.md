# PR-5B Concurrent State Integrity Boundary

## Decision

`@Transactional` makes a group of database writes atomic, but it does not prevent two transactions from
reading the same state and then overwriting each other. Core state is therefore decided by explicit SQL CAS:
the row id, resource relationship, expected status, and expected version must all match. A successful update
changes the protected aggregate fields and executes `version = version + 1` in the same statement. Java code
updates its in-memory version only after the database reports exactly one affected row.

Redis workflow locks remain a duplicate-work reduction mechanism. They are not an integrity boundary because
locks can expire, workers can be duplicated, and HTTP operators do not share the worker lock. MySQL is the
authoritative state arbiter.

## Versioned aggregates and transitions

- Workflow version protects `status`, `currentStep`, and `stateJson`.
- HumanReviewCase version protects status, assignment, resolution, resolution note, resolver, and resolution time.
- RefundCase version protects status, refund amount, and policy result.
- WorkflowStep, AuditLog, SessionEvent, and OutboxEvent are independent records and do not advance a core version.

The repository creation operation is named `insert`; existing aggregate state can only be changed through a
named CAS operation. The current transition matrix is:

| Aggregate | Expected state | Target state | Actor |
| --- | --- | --- | --- |
| Human Review | OPEN | ASSIGNED | authenticated operator |
| Human Review | OPEN, ASSIGNED | RESOLVED | authenticated operator |
| Workflow | CREATED | RUNNING | engine/worker |
| Workflow | RUNNING | RUNNING, RESPONDED, NEED_HUMAN, FAILED | engine/worker |
| Workflow | NEED_HUMAN | RESPONDED, FAILED | authenticated operator |
| Refund Case | NEED_HUMAN | ELIGIBLE, INELIGIBLE | authenticated operator |

`CREATED` and `RUNNING` are runnable, `NEED_HUMAN` is paused, and `RESPONDED` and `FAILED` are terminal.
ELIGIBLE and INELIGIBLE are terminal refund decisions. A Worker must not resume a paused Workflow.

## Transaction and lock order

Any transaction touching more than one of these records uses this order:

1. Workflow CAS
2. HumanReviewCase CAS
3. RefundCase CAS
4. WorkflowTask update
5. AuditLog
6. SessionEvent
7. transactional OutboxEvent

Human Review performs identity, tenant, resource-graph, state, and authoritative refund checks before the first
write. A CAS result of zero raises `CONCURRENT_STATE_CONFLICT`; a pre-existing invalid review command raises
`HUMAN_REVIEW_STATE_CONFLICT`; an authenticated resource-graph corruption raises
`DATA_INTEGRITY_CONFLICT`. The HTTP boundary returns redacted 409 responses without SQL or version details.
Cross-tenant resources remain concealed as 404.

If a later CAS conflicts, the exception leaves the service and the transaction rolls back earlier CAS writes,
AuditLog, SessionEvent, and transactional OutboxEvent rows. Trace persistence using `REQUIRES_NEW` is explicitly
outside this rollback guarantee.

## Worker conflict behavior

After taking the existing Redis lock, the Worker reloads both WorkflowTask and Workflow. RESPONDED and FAILED
are skipped as terminal; NEED_HUMAN is skipped as paused. A failed Workflow CAS triggers a database reload.
Settled Workflows map to SUCCEEDED, NEED_HUMAN, or CANCELLED task outcomes and record a skip reason. A version
change that still leaves the Workflow runnable uses the existing bounded task/outbox retry budget; it never
starts an immediate unbounded retry loop. Duplicate RabbitMQ deliveries therefore observe settled database
state and do not repeat the workflow.

## MySQL verification and external effects

Failsafe runs `*IT` tests under the `integration-test` Maven profile. MySQL Testcontainers supplies a real MySQL
8 instance. The tests use independent threads and Spring transactions, synchronize after both reads, enforce
timeouts, and close executors in `finally`. Normal `mvn test` does not select `*IT` tests or start Docker. The
integration profile fails if Docker or MySQL startup is unavailable.

A database rollback cannot undo an HTTP request, a broker send, or a third-party action that already occurred.
The current supplier lookup is read-only and is not described as rollback-safe. Any future real refund must be
requested only after the state CAS through a transactional OutboxEvent and must carry a stable supplier
idempotency key; the core transaction must not call a refund endpoint directly.
