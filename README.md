# TravelCare Agent

## 1. Project Overview

**TravelCare Agent** is an advanced customer service agent for the travel industry. The project is implemented across three main stages:
- **Stage 1 (Synchronous AI Flow):** Handles standard LLM interactions synchronously, processing incoming messages and delegating structured tasks to tool calling (e.g., retrieving itineraries, executing basic updates).
- **Stage 2 (Asynchronous Durable Workflow Engine):** Transforms the system into a resilient, event-driven, and durable workflow engine. It orchestrates long-running processes (like refunds or escalated human reviews) asynchronously, ensuring fault tolerance, idempotency, and persistence of intermediate states.
- **Stage 3 (Grounded Context Layer, Memory, RAG, and Context Assembly):** Establishes the agent's cognitive base. It introduces a Retrieval-Augmented Generation (RAG) system for active policy lookup, time-sensitive document filters, user preference/trip memory management, and a unified Context Assembler that tracks chunk citations and supports comprehensive debug APIs.

## 2. Architecture Highlights

- **Outbox Pattern (`workflow_tasks` table + RabbitMQ):** 
  Ensures reliable message delivery and state consistency. The system saves tasks in the database in the same transaction as business data (the "Outbox") and triggers asynchronous execution via RabbitMQ.
- **Concurrency Lock (Redis with Lua atomic unlock):** 
  Prevents race conditions by using distributed locking backed by Redis. Lua scripts are employed to ensure the check-and-release process of a lock is completely atomic.
- **Human-in-the-loop (工单流转):** 
  Supports suspending a workflow when a task requires human intervention (e.g., complex queries or special approvals). The workflow pauses, a case is routed to human operators, and once resolved, it injects the response back and resumes.
- **Self-Healing Scheduler:** 
  A periodic background job (Spring `@Scheduled`) identifies and recovers tasks stuck in a `PENDING` state for more than 2 minutes, protecting against worker crashes and ensuring eventual completion.
- **Grounded RAG Pipeline & Fulltext Search:**
  Queries ingested SOP policies in real-time. Employs MySQL FULLTEXT index searches with a programmatic wildcard `LIKE` search fallback if FULLTEXT yields 0 results. It filters out expired knowledge based on `effective_to` and `effective_from` dates.
- **Persistent Memory System:**
  Tracks user preferences and trip contexts. Restricts writes to sensitive, authoritative states (such as refund eligibility, order status, and payment state) using a non-authoritative memory barrier.
- **Context Assembler & Observability:**
  Assembles real-time session events, workflows, refund cases, RAG citations, and memories. Log files and assistant metadata automatically track and record citation chunk IDs.

## 3. Getting Started

### Prerequisites
You need Docker and Java (JDK 17+) installed.

#### Docker Setup Commands (MySQL, Redis, RabbitMQ)

```bash
# Start required infrastructure via Docker Compose
docker-compose up -d

# Or run individual containers if not using docker-compose:
docker run -d --name travelcare-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=travelcare -p 3306:3306 mysql:8.0
docker run -d --name travelcare-redis -p 6379:6379 redis:7.0
docker run -d --name travelcare-rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

### Application Build and Run Commands

```bash
# Build the application
.\mvnw.cmd clean package -DskipTests

# Run the application
.\mvnw.cmd spring-boot:run
```

## 4. API & Postman Verification Guide

Below are sample HTTP requests you can copy into Postman to verify the flow. 
*Assuming the application runs on `http://localhost:8080`.*

### 4.1. Creating a Session

```http
POST /api/sessions
Content-Type: application/json

{
  "userId": 1001,
  "channel": "WEB"
}
```
*Response will return the `sessionId`.*

### 4.2. Sending a Synchronous Message

```http
POST /api/sessions/1/messages
Content-Type: application/json

{
  "content": "Can you check my flight status?",
  "idempotencyKey": "msg-001",
  "async": false
}
```

### 4.3. Sending an Asynchronous Message

```http
POST /api/sessions/1/messages
Content-Type: application/json

{
  "content": "I want to refund my flight due to medical reasons.",
  "idempotencyKey": "msg-002",
  "async": true
}
```

### 4.4. Simulating Idempotency / Conflict Rejection
Send the exact same request again or with an existing idempotency key:

```http
POST /api/sessions/1/messages
Content-Type: application/json

{
  "content": "Is my refund processed?",
  "idempotencyKey": "msg-002",
  "async": true
}
```
*A repeated request with the same `idempotencyKey` will return the cached response, preventing duplicate tasks.*

### 4.5. Resolving a Human Case

```http
POST /api/cases/1/resolve
Content-Type: application/json

{
  "resolution": "RESOLVED",
  "resolutionNote": "Refund approved by admin."
}
```

### 4.6. Reading Workflow Status

```http
GET /api/workflows/status?sessionId=1
```
*Returns aggregate status including any pending task IDs, human case IDs, and refund case IDs.*

### 4.7. Ingesting Knowledge Document

```http
POST /api/knowledge/ingest
Content-Type: application/json

{
  "title": "Refund Policy SOP",
  "docType": "REFUND_SOP",
  "sourceUri": "https://example.com/refund-sop",
  "content": "Standard refund rules apply: paid tickets depart after 24 hours are refundable.",
  "effectiveFrom": "2026-05-01T00:00:00",
  "effectiveTo": "2026-12-31T23:59:59"
}
```

### 4.8. Searching Knowledge Base

```http
GET /api/knowledge/search?query=refund&docTypes=REFUND_SOP&limit=5
```

### 4.9. Creating User Memory

```http
POST /api/memories
Content-Type: application/json

{
  "userId": 1001,
  "memoryType": "USER_PREFERENCE",
  "memoryKey": "tone_preference",
  "memoryValue": "Prefers polite tone",
  "confidence": 0.95
}
```

### 4.10. Debugging Session Context

```http
GET /api/sessions/1/context?query=refund
```

## 5. Database Consistency Verification (SQLs)

Use these SQL queries to verify the database states directly.

```sql
-- Check active sessions
SELECT * FROM sessions ORDER BY id DESC LIMIT 10;

-- Check conversation events
SELECT * FROM session_events WHERE session_id = 1 ORDER BY seq_no ASC;

-- Check workflow tasks and their execution status (Outbox)
SELECT id, task_type, status, created_at, updated_at, last_error_message 
FROM workflow_tasks 
ORDER BY id DESC;

-- Check refund cases generated
SELECT * FROM refund_cases ORDER BY id DESC;

-- Check knowledge documents and chunks
SELECT * FROM knowledge_documents;
SELECT * FROM knowledge_chunks;

-- Check user agent memories
SELECT * FROM agent_memories;

-- Check audit logs for workflow state transitions and actions
SELECT * FROM audit_logs ORDER BY created_at DESC LIMIT 50;
```

## 6. Test Execution & Verification

### Test Stats Summary
The application contains a robust, transactional unit and integration test suite verifying synchronous flow, durable workflows, RAG, memory safety boundaries, session context aggregation, and concurrency safety.

- **Total Test Cases**: 79
- **Failures / Errors**: 0
- **Status**: 100% Passed (Green)
