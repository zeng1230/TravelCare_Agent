# PR-4A: Unified DLP & Redaction Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate sensitive information redaction and data-leakage prevention (DLP) across TravelCare-Agent into a single, unified service boundary covering debug APIs, handoff packets, trace queries, exception handling, logs, MDC, and metric tags.

**Architecture:** We will extend `RedactionService` with unified regex patterns and new helper methods. `EvaluationLeakageSanitizer` will become a facade delegating to `RedactionService`. A custom Logback `MessageConverter` will automatically redact all logged message text, while `TraceQueryService` will perform in-place redaction on queried trace entities at the reading boundary.

**Tech Stack:** Java 17, Spring Boot, SLF4J, Logback, MyBatis-Plus, JUnit 5, AssertJ.

## Global Constraints

- Do not use database column-level encryption or external enterprise DLP systems.
- Do not modify database schemas or tables.
- Source URI sanitization must remove query parameters containing `token`, `secret`, `key`, `signature`, or `authorization` and remove fragments, while keeping non-sensitive query params.
- Trace Query Service must perform redaction on raw trace entities in-place during read-only transactions, ensuring no persistence changes.
- Logback converter must instantiate its own `RedactionService` instance directly to avoid Spring Boot startup/lifecycle conflicts.

---

### Task 1: RedactionService Expansion & EvaluationLeakageSanitizer Integration

**Files:**
- Modify: `src/main/java/travelcare_agent/trace/RedactionService.java`
- Modify: `src/main/java/travelcare_agent/evaluation/EvaluationLeakageSanitizer.java`
- Modify: `src/test/java/travelcare_agent/trace/RedactionServiceTest.java`

**Interfaces:**
- Consumes: None
- Produces: `RedactionService.containsSensitiveLeakage(Object)`, `RedactionService.sanitizeSourceUri(String)`, `RedactionService.sanitizeLogField(String, int)`, `RedactionService.safeMetricTagValue(String)`.

- [ ] **Step 1: Expand RedactionService with consolidated regex patterns and helper methods**

Modify [RedactionService.java](file:///c:/javademo/TravelCare_Agent/src/main/java/travelcare_agent/trace/RedactionService.java) to include consolidated patterns and new helper methods.

Replace the pattern declaration and body with:
```java
package travelcare_agent.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
public class RedactionService {
    private static final String MASK = "[REDACTED]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "phone", "email", "idcard", "id_card", "token", "accesstoken", "access_token",
            "refreshtoken", "refresh_token", "password", "secret", "providersecret",
            "provider_secret", "apikey", "api_key", "authorization", "cookie",
            "rawprompt", "raw_prompt", "rawprovideroutput", "raw_provider_output",
            "rawmodeloutput", "rawtooloutput"
    );
    private static final Pattern EMAIL = Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(? Brabant)?(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(? Brabant)?(?<!\\d)\\d{17}[0-9Xx](?!\\w)");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile("(?i)(api[_-]?key|provider[_-]?secret|secret|token|credential|password|signature)\\s*[:=]\\s*[^\\s,;\\\"}]+");
    private static final Pattern URL_SECRET = Pattern.compile("(?i)([?&])(api[_-]?key|token|secret|credential|authorization|signature)=[^\\s&#`]+");
    private static final Pattern RAW_PROMPT_PROVIDER_OUTPUT = Pattern.compile("(?i)raw[_ -]?(prompt|provider[_ -]?output|model[_ -]?output|tool[_ -]?output)\\s*[:=][^\\n`]*");
    private static final Pattern BANK_CARD = Pattern.compile("(?i)(bank|card|银行卡|卡号)[^\\n]{0,32}\\b\\d{16,19}\\b");
    private static final Pattern STACK_FRAME = Pattern.compile("at\\s+[\\w.$_]+\\([^\\n\\r\\\\]*\\.java:\\d+\\)");

    // High-cardinality patterns for metrics safety
    private static final Pattern UUID_PATTERN = Pattern.compile("(?i).*\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b.*");
    private static final Pattern LONG_NUMBER_PATTERN = Pattern.compile(".*\\b\\d{8,}\\b.*");
    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("(?i).*\\bORD[-_]?\\d+\\b.*");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public RedactionResult redact(String value) {
        if (value == null) return new RedactionResult(null, 0);
        AtomicInteger count = new AtomicInteger();
        try {
            JsonNode node = objectMapper.readTree(value);
            redactNode(node, count);
            String json = objectMapper.writeValueAsString(node);
            return redactText(json, count);
        } catch (Exception ignored) {
            return redactText(value, count);
        }
    }

    public RedactionResult redactObject(Object value) {
        if (value == null) return new RedactionResult("{}", 0);
        try { return redact(objectMapper.writeValueAsString(value)); }
        catch (Exception ex) { return redact(String.valueOf(value)); }
    }

    private void redactNode(JsonNode node, AtomicInteger count) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (SENSITIVE_KEYS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
                    object.put(field.getKey(), MASK); count.incrementAndGet();
                } else redactNode(field.getValue(), count);
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(child -> redactNode(child, count));
        }
    }

    private RedactionResult redactText(String input, AtomicInteger initial) {
        String value = input;
        value = replace(value, BEARER, initial);
        value = replace(value, JWT, initial);
        value = replace(value, KEY_VALUE_SECRET, initial);
        value = replace(value, URL_SECRET, initial);
        value = replace(value, RAW_PROMPT_PROVIDER_OUTPUT, initial);
        value = replace(value, BANK_CARD, initial);
        value = replace(value, STACK_FRAME, initial);
        value = replace(value, EMAIL, initial);
        value = replace(value, PHONE, initial);
        value = replace(value, ID_CARD, initial);
        return new RedactionResult(value, initial.get());
    }

    private String replace(String value, Pattern pattern, AtomicInteger count) {
        var matcher = pattern.matcher(value); StringBuffer buffer = new StringBuffer();
        while (matcher.find()) { count.incrementAndGet(); matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(MASK)); }
        matcher.appendTail(buffer); return buffer.toString();
    }

    public boolean containsSensitiveLeakage(Object value) {
        if (value == null) return false;
        String text = String.valueOf(value);
        if (text.isBlank()) return false;
        return EMAIL.matcher(text).find()
                || PHONE.matcher(text).find()
                || ID_CARD.matcher(text).find()
                || BEARER.matcher(text).find()
                || JWT.matcher(text).find()
                || KEY_VALUE_SECRET.matcher(text).find()
                || URL_SECRET.matcher(text).find()
                || RAW_PROMPT_PROVIDER_OUTPUT.matcher(text).find()
                || BANK_CARD.matcher(text).find();
    }

    public String sanitizeSourceUri(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            URI uri = URI.create(value);
            String query = sanitizeQuery(uri.getRawQuery());
            return new URI(uri.getScheme(), uri.getRawAuthority(), uri.getRawPath(), query, null).toString();
        } catch (Exception ex) {
            return redact(value).value();
        }
    }

    private String sanitizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            String key = pair;
            String val = "";
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                key = pair.substring(0, eq);
                val = pair.substring(eq + 1);
            }
            String normalized = key.toLowerCase(Locale.ROOT);
            if (normalized.contains("token") || normalized.contains("secret") || normalized.contains("key")
                    || normalized.contains("signature") || normalized.contains("authorization")) {
                continue;
            }
            kept.add(key + (eq >= 0 ? "=" + URLEncoder.encode(val, StandardCharsets.UTF_8) : ""));
        }
        return kept.isEmpty() ? null : String.join("&", kept);
    }

    public String sanitizeLogField(String value, int limit) {
        if (value == null) return "";
        String redacted = redact(value).value();
        return redacted.length() > limit ? redacted.substring(0, limit) : redacted;
    }

    public String safeMetricTagValue(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String trimmed = value.trim();
        String lower = trimmed.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        boolean isSensitive = SENSITIVE_KEYS.stream().anyMatch(lower::contains)
                || UUID_PATTERN.matcher(trimmed).matches()
                || LONG_NUMBER_PATTERN.matcher(trimmed).matches()
                || BEARER.matcher(trimmed).matches()
                || JWT.matcher(trimmed).matches()
                || EMAIL.matcher(trimmed).matches()
                || PHONE.matcher(trimmed).matches()
                || ID_CARD.matcher(trimmed).matches()
                || ORDER_NO_PATTERN.matcher(trimmed).matches();

        if (isSensitive) {
            return "REDACTED";
        }
        return trimmed.length() > 64 ? "REDACTED" : trimmed;
    }

    public record RedactionResult(String value, int redactedCount) {}
}
```

- [ ] **Step 2: Refactor EvaluationLeakageSanitizer as a delegate facade**

Modify [EvaluationLeakageSanitizer.java](file:///c:/javademo/TravelCare_Agent/src/main/java/travelcare_agent/evaluation/EvaluationLeakageSanitizer.java).

Replace its contents to delegate to `RedactionService`:
```java
package travelcare_agent.evaluation;

import travelcare_agent.trace.RedactionService;

public final class EvaluationLeakageSanitizer {
    private static final RedactionService service = new RedactionService();

    private EvaluationLeakageSanitizer() {
    }

    public static boolean containsSensitiveLeakage(Object value) {
        return service.containsSensitiveLeakage(value);
    }

    public static String redact(String value) {
        if (value == null || value.isBlank()) return value;
        return service.redact(value).value();
    }
}
```

- [ ] **Step 3: Run existing tests to verify compiling**

Run: `.\mvnw.cmd "-Dtest=RedactionServiceTest" test`
Expected: PASS

---

### Task 2: Output Surface & Interception Protection

**Files:**
- Modify: `src/main/java/travelcare_agent/human/packet/HumanHandoffContextPacketBuilder.java`
- Modify: `src/main/java/travelcare_agent/agentops/AgentOpsDebugService.java`
- Modify: `src/main/java/travelcare_agent/common/exception/GlobalExceptionHandler.java`
- Create: `src/main/java/travelcare_agent/common/logging/RedactingMessageConverter.java`
- Create: `src/main/resources/logback-spring.xml`

**Interfaces:**
- Consumes: `RedactionService.sanitizeSourceUri(String)`, `RedactingMessageConverter` (Logback rule).
- Produces: Redacted output API data and logs.

- [ ] **Step 1: Reuse sanitizeSourceUri in HumanHandoffContextPacketBuilder**

Modify [HumanHandoffContextPacketBuilder.java](file:///c:/javademo/TravelCare_Agent/src/main/java/travelcare_agent/human/packet/HumanHandoffContextPacketBuilder.java).

Remove the private `sanitizeSourceUri` and `sanitizeQuery` methods (lines 421-455) and replace calls with `redactionService.sanitizeSourceUri(...)`. Also apply `redactionService.redact(...)` on raw message summary contents.

For line 362:
```diff
-                    sanitizeSourceUri(text(value, "sourceUri")),
+                    redactionService.sanitizeSourceUri(text(value, "sourceUri")),
```

- [ ] **Step 2: Reuse sanitizeSourceUri in AgentOpsDebugService**

Modify [AgentOpsDebugService.java](file:///c:/javademo/TravelCare_Agent/src/main/java/travelcare_agent/agentops/AgentOpsDebugService.java).

Remove private `sanitizeSourceUri` and `sanitizeQuery` (lines 358-392) and replace calls with `redactionService.sanitizeSourceUri(...)`. Ensure debug questions and response text are fully passed through `redactionService.redact(...)`.

- [ ] **Step 3: Redact exception responses in GlobalExceptionHandler**

Modify [GlobalExceptionHandler.java](file:///c:/javademo/TravelCare_Agent/src/main/java/travelcare_agent/common/exception/GlobalExceptionHandler.java).

Apply `redactionService.redact(...)` to the error messages returned to the client:
```java
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex) {
        String msg = ex.getMessage();
        String redacted = msg == null ? null : redactionService.redact(msg).value();
        return ResponseEntity.status(resolveStatus(ex.getResultCode()))
                .body(Result.fail(ex.getResultCode(), redacted));
    }
```
And similarly for validation error responses.

- [ ] **Step 4: Create Logback RedactingMessageConverter**

Create [RedactingMessageConverter.java](file:///c:/javademo/TravelCare_Agent/src/main/java/travelcare_agent/common/logging/RedactingMessageConverter.java):
```java
package travelcare_agent.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import travelcare_agent.trace.RedactionService;

public class RedactingMessageConverter extends MessageConverter {
    private final RedactionService redactionService = new RedactionService();

    @Override
    public String convert(ILoggingEvent event) {
        String msg = super.convert(event);
        if (msg == null || msg.isEmpty()) {
            return msg;
        }
        return redactionService.redact(msg).value();
    }
}
```

- [ ] **Step 5: Create logback-spring.xml**

Create [logback-spring.xml](file:///c:/javademo/TravelCare_Agent/src/main/resources/logback-spring.xml):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <conversionRule conversionWord="redactMsg" converterClass="travelcare_agent.common.logging.RedactingMessageConverter" />
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] [%X{traceId:-}] %logger{36} - %redactMsg%n</pattern>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```

---

### Task 3: Trace Query Boundary Idempotent Redaction

**Files:**
- Modify: `src/main/java/travelcare_agent/trace/TraceQueryService.java`

**Interfaces:**
- Consumes: `RedactionService`
- Produces: Redacted trace entity records inside `TraceQueryService.get()` and `diagnostics()`.

- [ ] **Step 1: Perform in-place redaction on trace detail querying**

Modify [TraceQueryService.java](file:///c:/javademo/TravelCare_Agent/src/main/java/travelcare_agent/trace/TraceQueryService.java) to autowire `RedactionService`. Update the `get()` method to loop over fetched records and redact their contents before returning:

```java
    private final RedactionService redactionService;

    public TraceQueryService(TraceRunRepository runs, TraceSpanRepository spans, TraceEventRepository events,
            TraceSnapshotRepository snapshots, ObjectMapper objectMapper, RedactionService redactionService) {
        this.runs=runs; this.spans=spans; this.events=events; this.snapshots=snapshots; this.objectMapper=objectMapper;
        this.redactionService = redactionService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public TraceDetail get(String traceId) {
        TraceRun run = require(traceId);
        run.setErrorMessage(redactionService.redact(run.getErrorMessage()).value());
        run.setMetadataJson(redactionService.redact(run.getMetadataJson()).value());

        List<TraceSpan> spanList = spans.findByTraceId(traceId);
        for (TraceSpan s : spanList) {
            s.setErrorMessage(redactionService.redact(s.getErrorMessage()).value());
            s.setInputRef(redactionService.redact(s.getInputRef()).value());
            s.setOutputRef(redactionService.redact(s.getOutputRef()).value());
            s.setMetadataJson(redactionService.redact(s.getMetadataJson()).value());
        }

        List<TraceEvent> eventList = events.findByTraceId(traceId);
        for (TraceEvent e : eventList) {
            e.setMetadataJson(redactionService.redact(e.getMetadataJson()).value());
        }

        List<TraceSnapshot> snapshotList = snapshots.findByTraceId(traceId);
        for (TraceSnapshot sn : snapshotList) {
            sn.setPayloadJson(redactionService.redact(sn.getPayloadJson()).value());
            if ("CITATION_SUMMARY".equals(sn.getSnapshotType())) {
                // Specifically sanitize all sourceUris in the payload via sanitizeSourceUri method
                // (or it is already redacted via Key-Value and query regex patterns inside redact)
            }
        }

        return new TraceDetail(run, spanList, eventList, snapshotList);
    }
```

---

### Task 4: Log Message & Metric Tag Safety Integration

**Files:**
- Modify: `src/main/java/travelcare_agent/workflow/WorkflowTaskWorker.java`
- Modify: `src/main/java/travelcare_agent/observability/TravelCareMetrics.java`

**Interfaces:**
- Consumes: `RedactionService.sanitizeLogField(String, int)`, `RedactionService.safeMetricTagValue(String)`.
- Produces: Redacted workflow logs and metrics.

- [ ] **Step 1: Inject RedactionService and replace safeLogMessage in WorkflowTaskWorker**

Modify [WorkflowTaskWorker.java](file:///c:/javademo/TravelCare_Agent/src/main/java/travelcare_agent/workflow/WorkflowTaskWorker.java).

Inject `RedactionService` into the constructor and update the `safeLogMessage` private method:
```java
    private final RedactionService redactionService;

    // Inside constructor:
    // Add RedactionService redactionService to parameters list and assign to field
```
And replace the body of `safeLogMessage`:
```java
    private String safeLogMessage(String value) {
        return redactionService.sanitizeLogField(value, 160);
    }
```

- [ ] **Step 2: Delegate safeValue check to safeMetricTagValue in TravelCareMetrics**

Modify [TravelCareMetrics.java](file:///c:/javademo/TravelCare_Agent/src/main/java/travelcare_agent/observability/TravelCareMetrics.java) to inject `RedactionService`. Update the private `safeValue` method to delegate to the unified `safeMetricTagValue` function:

```java
    private final RedactionService redactionService;

    @org.springframework.beans.factory.annotation.Autowired
    public TravelCareMetrics(MeterRegistry registry, RedactionService redactionService) {
        this.registry = registry;
        this.redactionService = redactionService;
    }

    private String safeValue(String value) {
        return redactionService.safeMetricTagValue(value);
    }
```

---

### Task 5: Testing Matrix Verification

**Files:**
- Modify: `src/test/java/travelcare_agent/trace/RedactionServiceTest.java`
- Modify: `src/test/java/travelcare_agent/human/HumanHandoffContextPacketBuilderTest.java`

**Interfaces:**
- Consumes: JUnit tests
- Produces: Completed tests validating DLP.

- [ ] **Step 1: Write expanded Field-Level Redaction matrix tests**

Modify [RedactionServiceTest.java](file:///c:/javademo/TravelCare_Agent/src/test/java/travelcare_agent/trace/RedactionServiceTest.java) to verify all phone, email, idcard, Bearer, JWT, Key-Value secrets, URL query parameter secrets, raw prompt lines, and raw provider outputs are successfully replaced with `[REDACTED]`.

- [ ] **Step 2: Add validation tests for Handoff and Debug API boundaries**

Verify that sensitive customer fields in the `HumanHandoffContextPacketBuilderTest` are redacted, and that `sourceUri` query parameter secrets are stripped out.

- [ ] **Step 3: Run the full test suite to guarantee zero regression**

Run: `.\mvnw.cmd test`
Expected: All tests pass.
