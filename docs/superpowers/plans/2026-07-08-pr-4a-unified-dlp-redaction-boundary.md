# PR-4A: Unified DLP & Redaction Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify and harden the data-leakage prevention (DLP) boundary across all output surfaces (AgentOps, Handoff Packet, Trace Query API, Exception Handling, Logging, and Metrics) utilizing a consolidated and robust `RedactionService`.

**Architecture:** We will extend `RedactionService` with enhanced pattern matrices and core utility methods. `EvaluationLeakageSanitizer` will be refactored into a facade delegating directly to `RedactionService`. `TraceQueryService` will apply dynamic runtime redaction to trace entities. `GlobalExceptionHandler`, logging MDC, and log messages (via Logback custom MessageConverter) will be automatically sanitized.

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, Logback, SLF4J, JUnit 5, AssertJ.

## Global Constraints
- Target Java version is 17.
- Do not make external calls or depend on third-party online DLP providers.
- Maintain mock-provider execution by default.
- Never write database migrations or alter existing table structures.

---

### Task 1: RedactionService Pattern Expansion & Core API

**Files:**
- Modify: `src/main/java/travelcare_agent/trace/RedactionService.java`
- Modify: `src/test/java/travelcare_agent/trace/RedactionServiceTest.java`

**Interfaces:**
- Produces:
  - `public boolean containsSensitiveLeakage(Object value)`
  - `public String sanitizeSourceUri(String value)`
  - `public String sanitizeLogField(String value, int limit)`
  - `public String safeMetricTagValue(String value)`

- [ ] **Step 1: Write expanded tests in RedactionServiceTest**
  Update the test file to cover a matrix of phone numbers, emails, ID cards, Bearer tokens, JWTs, key-value secrets, raw prompt lines, raw provider output lines, bank cards, and sourceUri query parameters.
  
  ```java
  // In src/test/java/travelcare_agent/trace/RedactionServiceTest.java
  @Test
  void testUnifiedDlpRedactionMatrix() {
      RedactionService service = new RedactionService();
      // Test URL sanitization
      assertThat(service.sanitizeSourceUri("https://example.com/sop?token=secret&id=123#frag"))
          .isEqualTo("https://example.com/sop?id=123");
      // Test mobile number
      assertThat(service.redact("my number is 13812345678").value())
          .contains("[REDACTED]");
      // Test contains leakage
      assertThat(service.containsSensitiveLeakage("Bearer eyJ..."))
          .isTrue();
  }
  ```

- [ ] **Step 2: Run tests to verify failure**
  Run: `.\mvnw.cmd "-Dtest=RedactionServiceTest" test`
  Expected: Compile errors or FAIL because methods do not exist or patterns are incomplete.

- [ ] **Step 3: Update patterns and implement new methods in RedactionService**
  Modify `src/main/java/travelcare_agent/trace/RedactionService.java` to define new sensitive keys, regex pattern lists, and implement:
  - `containsSensitiveLeakage(Object)`
  - `sanitizeSourceUri(String)`
  - `sanitizeLogField(String, int)`
  - `safeMetricTagValue(String)`
  - Modify `redactNode` to recursively intercept keys like `sourceUri` or `source_uri` and call `sanitizeSourceUri` on their string values.

- [ ] **Step 4: Run tests to verify they pass**
  Run: `.\mvnw.cmd "-Dtest=RedactionServiceTest" test`
  Expected: PASS

- [ ] **Step 5: Commit changes**
  Run: `git add src/main/java/travelcare_agent/trace/RedactionService.java src/test/java/travelcare_agent/trace/RedactionServiceTest.java; git commit -m "feat(dlp): expand RedactionService patterns and utility methods"`

---

### Task 2: EvaluationLeakageSanitizer Facade Refactoring

**Files:**
- Modify: `src/main/java/travelcare_agent/evaluation/EvaluationLeakageSanitizer.java`
- Modify: `src/test/java/travelcare_agent/evaluation/Stage9EvaluationReportWriterTest.java` (if exists, or create a new test)

**Interfaces:**
- Consumes: `travelcare_agent.trace.RedactionService`
- Produces: `EvaluationLeakageSanitizer` static delegate calls matching previous signatures.

- [ ] **Step 1: Update EvaluationLeakageSanitizer to delegate to RedactionService**
  Replace contents of `EvaluationLeakageSanitizer.java` to act as a wrapper around a static instance of `RedactionService`. Ensure signatures `containsSensitiveLeakage(Object)` and `redact(String)` are maintained.

- [ ] **Step 2: Run evaluation integration tests**
  Run: `.\mvnw.cmd "-Dtest=*Evaluation*" test`
  Expected: PASS

- [ ] **Step 3: Commit changes**
  Run: `git add src/main/java/travelcare_agent/evaluation/EvaluationLeakageSanitizer.java; git commit -m "refactor(dlp): turn EvaluationLeakageSanitizer into a RedactionService facade"`

---

### Task 3: TraceQueryService Dynamic Read-Boundary Redaction

**Files:**
- Modify: `src/main/java/travelcare_agent/trace/TraceQueryService.java`
- Modify: `src/test/java/travelcare_agent/trace/TraceQueryServiceTest.java` (create if missing)

**Interfaces:**
- Consumes: `travelcare_agent.trace.RedactionService`

- [ ] **Step 1: Write a failing test in TraceQueryServiceTest**
  Write a test that inserts raw sensitive values (like raw tokens, raw prompt lines, or query secrets in sourceUris) in `TraceRun`, `TraceSpan`, `TraceEvent`, and `TraceSnapshot` entities, and verifies that `TraceQueryService.get()` and `diagnostics()` return in-place sanitized results.

- [ ] **Step 2: Run test to verify failure**
  Run: `.\mvnw.cmd "-Dtest=TraceQueryServiceTest" test`
  Expected: FAIL (sensitive data leaked)

- [ ] **Step 3: Implement dynamic redaction in TraceQueryService**
  Inject `RedactionService` into `TraceQueryService`. In `get(String)` and `diagnostics(String)`, iterate over retrieved collections of `TraceSpan`, `TraceEvent`, and `TraceSnapshot`, mutating their JSON payload/metadata/message properties using `redactionService.redact(...)` and cleaning citation `sourceUri` fields via `sanitizeSourceUri(...)` before returning.

- [ ] **Step 4: Run test to verify it passes**
  Run: `.\mvnw.cmd "-Dtest=TraceQueryServiceTest" test`
  Expected: PASS

- [ ] **Step 5: Commit changes**
  Run: `git add src/main/java/travelcare_agent/trace/TraceQueryService.java src/test/java/travelcare_agent/trace/TraceQueryServiceTest.java; git commit -m "feat(dlp): enforce read-boundary dynamic redaction in TraceQueryService"`

---

### Task 4: Output Surface Integration (Packet, Debug, Handler, Logs, Metrics)

**Files:**
- Modify: `src/main/java/travelcare_agent/human/packet/HumanHandoffContextPacketBuilder.java`
- Modify: `src/main/java/travelcare_agent/agentops/AgentOpsDebugService.java`
- Modify: `src/main/java/travelcare_agent/common/exception/GlobalExceptionHandler.java`
- Modify: `src/main/java/travelcare_agent/workflow/WorkflowTaskWorker.java`
- Modify: `src/main/java/travelcare_agent/observability/TravelCareMetrics.java`
- Modify: `src/main/java/travelcare_agent/common/trace/TraceIdFilter.java`
- Modify: `src/main/java/travelcare_agent/trace/TraceContextHolder.java`
- Create: `src/main/java/travelcare_agent/common/logging/RedactingMessageConverter.java`
- Create: `src/main/resources/logback-spring.xml`
- Test: Modify WebMvc and unit tests

- [ ] **Step 1: Write logback MessageConverter and logback-spring.xml**
  Create `RedactingMessageConverter.java` extending `ch.qos.logback.classic.pattern.MessageConverter` to redact messages using `RedactionService`. Create `logback-spring.xml` registering this converter as `<conversionRule conversionWord="redactMsg" converterClass="..." />` and using `%redactMsg` in the console appender pattern.

- [ ] **Step 2: Update MDC filters, exceptions, and builders**
  - Update `TraceIdFilter` and `TraceContextHolder` to sanitize traceIds/values before placing them in the MDC.
  - Update `GlobalExceptionHandler` to redact response messages in `handleBusinessException` and `handleValidationException`.
  - Update `HumanHandoffContextPacketBuilder` and `AgentOpsDebugService` to use unified `redactionService.sanitizeSourceUri(...)` instead of duplicative private cleaning methods.
  - Update `WorkflowTaskWorker.java` to delegate log message filtering to `redactionService.sanitizeLogField(value, 160)`.
  - Update `TravelCareMetrics.java` to clean tags using `redactionService.safeMetricTagValue(value)`.

- [ ] **Step 3: Run full integration tests**
  Run: `.\mvnw.cmd test`
  Expected: PASS

- [ ] **Step 4: Commit changes**
  Run: `git add . ; git commit -m "feat(dlp): complete output surface integration for unified redaction boundary"`
