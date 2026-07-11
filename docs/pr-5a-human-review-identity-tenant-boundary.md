# PR-5A Human Review Identity and Tenant Boundary

## Decision record

Human Review is a high-risk server-controlled workflow. Assign and resolve operations use the authenticated
`CurrentUser` as their only actor and tenant source. Client payloads cannot select an operator. The assign
endpoint accepts no fields, and resolve accepts only a constrained resolution and optional note.

Human Review cases persist their tenant. Historical rows are backfilled from their required Session relation;
the migration deliberately fails if a row cannot be attributed safely. Reads use tenant-scoped repository
queries and conceal cross-tenant resources as not found. The primary-key index on `id` remains sufficient for
point lookup, while `(tenant_id, status)` supports tenant work queues.

Tenant equality alone is insufficient authorization. Before any state or event write, the service validates the
whole resource relationship: Review to Session, Workflow to Session, and optional RefundCase to both tenant and
Workflow. Failed authorization, relationship, state, or approval checks produce no Review, Audit, Session Event,
Workflow, or RefundCase partial update within the existing transaction.

System creation and human processing use separate audit APIs. System creation records `SYSTEM` /
`travelcare-agent` with the tenant derived from the validated Session. Assign and resolve record `OPERATOR`,
with actor ID and tenant read inside AuditService from the Spring Security context.

## State and resolution rules

- Assign permits only `OPEN -> ASSIGNED`.
- Resolve permits `OPEN -> RESOLVED` and `ASSIGNED -> RESOLVED`.
- Terminal or repeated transitions return HTTP 409.
- Resolution is exactly `APPROVED` or `REJECTED`; invalid input returns HTTP 400.
- `APPROVED` remains subject to the authoritative refund evidence gate.

## Glossary

- **SYSTEM actor**: automated TravelCare backend work, identified as `travelcare-agent`.
- **Authenticated operator**: an OPERATOR or ADMIN acting through a valid JWT; actor ID is JWT `userId`.
- **Tenant ownership**: the JWT tenant must equal the persisted Review and Session tenant.
- **Resource relationship**: the required Session, Workflow, Review, and optional RefundCase linkage.
- **Resource concealment**: returning not found for both absent and cross-tenant Review IDs.
- **Authoritative refund gate**: the policy preventing approval without verified durable refund evidence.
