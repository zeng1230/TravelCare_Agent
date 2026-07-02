package travelcare_agent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Component
public class TravelCareMetrics {
    private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
            "userid", "tenantid", "sessionid", "workflowid", "orderno",
            "traceid", "prompt", "token", "secret"
    );
    private static final Set<String> ALLOWED_MODELS = Set.of("mock-stage10a", "deepseek-chat", "unknown");
    private static final Pattern UUID = Pattern.compile("(?i).*\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b.*");
    private static final Pattern LONG_NUMBER = Pattern.compile(".*\\b\\d{8,}\\b.*");
    private static final Pattern BEARER = Pattern.compile("(?i).*Bearer\\s+[A-Za-z0-9._~+/-]+=*.*");
    private static final Pattern JWT = Pattern.compile(".*eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+.*");
    private static final Pattern EMAIL = Pattern.compile("(?i).*[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}.*");
    private static final Pattern PHONE = Pattern.compile(".*(?<!\\d)1[3-9]\\d{9}(?!\\d).*");
    private static final Pattern ORDER_NO = Pattern.compile("(?i).*\\bORD[-_]?\\d+\\b.*");

    private final MeterRegistry registry;

    public TravelCareMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void workflowStarted(String workflowType, String currentStep) {
        counter("travelcare.workflow.started.total", Map.of(
                "workflowType", workflowType,
                "currentStep", currentStep,
                "status", "RUNNING"
        ));
    }

    public void workflowCompleted(String workflowType, String status, String currentStep, Duration duration) {
        counter("travelcare.workflow.completed.total", Map.of(
                "workflowType", workflowType,
                "status", status,
                "currentStep", currentStep
        ));
        timer("travelcare.workflow.duration", duration, Map.of(
                "workflowType", workflowType,
                "status", status,
                "currentStep", currentStep
        ));
    }

    public void workflowNeedHuman(String workflowType, String currentStep, String failureCode, Duration duration) {
        counter("travelcare.workflow.need_human.total", Map.of(
                "workflowType", workflowType,
                "status", "NEED_HUMAN",
                "currentStep", currentStep,
                "failureCode", failureCode
        ));
        timer("travelcare.workflow.duration", duration, Map.of(
                "workflowType", workflowType,
                "status", "NEED_HUMAN",
                "currentStep", currentStep,
                "failureCode", failureCode
        ));
    }

    public void workflowFailed(String workflowType, String currentStep, String failureCode, Duration duration) {
        counter("travelcare.workflow.failed.total", Map.of(
                "workflowType", workflowType,
                "status", "FAILED",
                "currentStep", currentStep,
                "failureCode", failureCode
        ));
        timer("travelcare.workflow.duration", duration, Map.of(
                "workflowType", workflowType,
                "status", "FAILED",
                "currentStep", currentStep,
                "failureCode", failureCode
        ));
    }

    public void toolStarted(String toolName, boolean sideEffecting) {
        counter("travelcare.tool.call.started.total", Map.of(
                "toolName", toolName,
                "sideEffecting", String.valueOf(sideEffecting),
                "status", "RUNNING"
        ));
    }

    public void toolCompleted(String toolName, boolean sideEffecting, Duration duration) {
        counter("travelcare.tool.call.completed.total", Map.of(
                "toolName", toolName,
                "sideEffecting", String.valueOf(sideEffecting),
                "status", "SUCCESS"
        ));
        timer("travelcare.tool.call.duration", duration, Map.of(
                "toolName", toolName,
                "sideEffecting", String.valueOf(sideEffecting),
                "status", "SUCCESS"
        ));
    }

    public void toolFailed(String toolName, boolean sideEffecting, String failureCode, Duration duration) {
        counter("travelcare.tool.call.failed.total", Map.of(
                "toolName", toolName,
                "sideEffecting", String.valueOf(sideEffecting),
                "status", "FAILED",
                "failureCode", failureCode
        ));
        timer("travelcare.tool.call.duration", duration, Map.of(
                "toolName", toolName,
                "sideEffecting", String.valueOf(sideEffecting),
                "status", "FAILED",
                "failureCode", failureCode
        ));
    }

    public void toolUnknown(String toolName, boolean sideEffecting, String failureCode, Duration duration) {
        counter("travelcare.tool.call.unknown.total", Map.of(
                "toolName", toolName,
                "sideEffecting", String.valueOf(sideEffecting),
                "status", "UNKNOWN",
                "failureCode", failureCode
        ));
        timer("travelcare.tool.call.duration", duration, Map.of(
                "toolName", toolName,
                "sideEffecting", String.valueOf(sideEffecting),
                "status", "UNKNOWN",
                "failureCode", failureCode
        ));
    }

    public void toolSkipped(String toolName, boolean sideEffecting, String status) {
        counter("travelcare.tool.call.skipped.total", Map.of(
                "toolName", toolName,
                "sideEffecting", String.valueOf(sideEffecting),
                "status", status
        ));
    }

    public void toolRetry(String toolName, boolean sideEffecting, String failureCode) {
        counter("travelcare.tool.call.retry.total", Map.of(
                "toolName", toolName,
                "sideEffecting", String.valueOf(sideEffecting),
                "failureCode", failureCode
        ));
    }

    public void llmRequest(String provider, String model, String mode) {
        counter("travelcare.llm.request.total", llmTags(provider, model, mode, "REQUEST", null, null));
    }

    public void llmSuccess(String provider, String model, String mode, String safetyDecision,
                           Duration latency, Integer inputTokens, Integer outputTokens) {
        counter("travelcare.llm.success.total", llmTags(provider, model, mode, "SUCCESS", null, safetyDecision));
        timer("travelcare.llm.latency", latency, llmTags(provider, model, mode, "SUCCESS", null, safetyDecision));
        tokenSummary("travelcare.llm.input_tokens", inputTokens, llmTags(provider, model, mode, "SUCCESS", null, safetyDecision));
        tokenSummary("travelcare.llm.output_tokens", outputTokens, llmTags(provider, model, mode, "SUCCESS", null, safetyDecision));
    }

    public void llmFailure(String provider, String model, String mode, String failureCode,
                           Duration latency, Integer inputTokens, Integer outputTokens) {
        counter("travelcare.llm.failure.total", llmTags(provider, model, mode, "FAILURE", failureCode, null));
        timer("travelcare.llm.latency", latency, llmTags(provider, model, mode, "FAILURE", failureCode, null));
        tokenSummary("travelcare.llm.input_tokens", inputTokens, llmTags(provider, model, mode, "FAILURE", failureCode, null));
        tokenSummary("travelcare.llm.output_tokens", outputTokens, llmTags(provider, model, mode, "FAILURE", failureCode, null));
    }

    public void llmFallback(String provider, String model, String mode, String failureCode) {
        counter("travelcare.llm.fallback.total", llmTags(provider, model, mode, "FALLBACK", failureCode, null));
    }

    public void llmSafetyBlock(String provider, String model, String mode, String safetyDecision, String reasonCode) {
        counter("travelcare.llm.safety_block.total", llmTags(provider, model, mode, "SAFETY_BLOCK", reasonCode, safetyDecision));
    }

    public void safetyDecision(String decision, String reasonCode, String providerMode) {
        counter("travelcare.safety.decision.total", Map.of(
                "decision", decision,
                "reasonCode", reasonCode,
                "providerMode", providerMode
        ));
        if ("BLOCK".equals(decision)) {
            counter("travelcare.safety.blocked.total", Map.of("decision", decision, "reasonCode", reasonCode, "providerMode", providerMode));
        } else if ("ALLOW".equals(decision)) {
            counter("travelcare.safety.allowed.total", Map.of("decision", decision, "reasonCode", reasonCode, "providerMode", providerMode));
        } else if ("FALLBACK".equals(decision)) {
            counter("travelcare.safety.fallback.total", Map.of("decision", decision, "reasonCode", reasonCode, "providerMode", providerMode));
        }
    }

    public void outboxCreated(String eventType) {
        counter("travelcare.outbox.event.created.total", Map.of("eventType", eventType));
    }

    public void outboxPublished(String eventType, Duration latency) {
        counter("travelcare.outbox.event.published.total", Map.of("eventType", eventType, "status", "PUBLISHED"));
        timer("travelcare.outbox.publish.latency", latency, Map.of("eventType", eventType, "status", "PUBLISHED"));
    }

    public void outboxRetry(String eventType, String failureCode) {
        counter("travelcare.outbox.event.retry.total", Map.of("eventType", eventType, "failureCode", failureCode));
    }

    public void outboxFailed(String eventType, String failureCode) {
        counter("travelcare.outbox.event.failed.total", Map.of("eventType", eventType, "failureCode", failureCode));
    }

    public void workerStarted(String taskType) {
        counter("travelcare.worker.task.started.total", Map.of("taskType", taskType));
    }

    public void workerSucceeded(String taskType) {
        counter("travelcare.worker.task.succeeded.total", Map.of("taskType", taskType, "status", "SUCCEEDED"));
    }

    public void workerFailed(String taskType, String failureCode) {
        counter("travelcare.worker.task.failed.total", Map.of("taskType", taskType, "failureCode", failureCode));
    }

    public void workerRetryScheduled(String taskType, String failureCode) {
        counter("travelcare.worker.task.retry_scheduled.total", Map.of("taskType", taskType, "failureCode", failureCode));
    }

    public void workerSkipped(String taskType, String reasonCode) {
        counter("travelcare.worker.task.skipped.total", Map.of("taskType", taskType, "reasonCode", reasonCode));
    }

    public void workerDeadLettered(String taskType, String failureCode) {
        counter("travelcare.worker.task.dead_lettered.total", Map.of("taskType", taskType, "failureCode", failureCode));
    }

    public void reconciliationCreated(String sourceType, String reasonCode) {
        counter("travelcare.reconciliation.created.total", Map.of("sourceType", sourceType, "reasonCode", reasonCode));
    }

    public void reconciliationResolved(String sourceType, String status, String resultCode) {
        String meter = "CONFIRMED_SUCCESS".equals(status)
                ? "travelcare.reconciliation.resolved_success.total"
                : "CONFIRMED_FAILED".equals(status)
                ? "travelcare.reconciliation.resolved_failed.total"
                : "travelcare.reconciliation.unknown.total";
        counter(meter, Map.of("sourceType", sourceType, "status", status, "resultCode", resultCode));
    }

    public void recordSupplierCall(String adapter, String supplier, String outcome, Duration duration) {
        Map<String, String> tags = Map.of(
                "adapter", adapter,
                "supplier", supplier,
                "outcome", outcome
        );
        counter("travelcare.supplier.requests.total", tags);
        timer("travelcare.supplier.latency", duration, tags);
        if (isSupplierFailureOutcome(outcome)) {
            counter("travelcare.supplier.failures.total", tags);
        }
    }

    public void gauge(String name, Supplier<Number> supplier) {
        Gauge.builder(name, supplier, value -> value.get().doubleValue()).register(registry);
    }

    public void counter(String name, Map<String, String> tags) {
        Counter.builder(name).tags(safeTags(tags)).register(registry).increment();
    }

    public void timer(String name, Duration duration, Map<String, String> tags) {
        Timer.builder(name).tags(safeTags(tags)).register(registry)
                .record(Math.max(0, duration == null ? 0 : duration.toNanos()), TimeUnit.NANOSECONDS);
    }

    private void tokenSummary(String name, Integer value, Map<String, String> tags) {
        if (value == null) return;
        DistributionSummary.builder(name).tags(safeTags(tags)).register(registry).record(Math.max(0, value));
    }

    private static boolean isSupplierFailureOutcome(String outcome) {
        return Set.of("timeout", "unavailable", "invalid_response", "bad_request", "connection_failed")
                .contains(outcome);
    }

    private static Map<String, String> llmTags(String provider, String model, String mode, String result,
                                               String failureCode, String safetyDecision) {
        java.util.LinkedHashMap<String, String> tags = new java.util.LinkedHashMap<>();
        tags.put("provider", provider);
        tags.put("model", normalizeModel(model));
        tags.put("mode", mode);
        tags.put("result", result);
        if (failureCode != null) tags.put("failureCode", failureCode);
        if (safetyDecision != null) tags.put("safetyDecision", safetyDecision);
        return tags;
    }

    public static String normalizeModel(String model) {
        if (model == null) return "unknown";
        String value = model.trim();
        if (ALLOWED_MODELS.contains(value)) return value;
        return "unknown";
    }

    private static Tags safeTags(Map<String, String> tags) {
        List<Tag> safe = new ArrayList<>();
        tags.forEach((key, value) -> {
            if (key == null || forbiddenKey(key)) return;
            safe.add(Tag.of(key, safeValue(value)));
        });
        return Tags.of(safe);
    }

    private static boolean forbiddenKey(String key) {
        String normalized = key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return FORBIDDEN_TAG_KEYS.stream().anyMatch(normalized::contains);
    }

    private static String safeValue(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String trimmed = value.trim();
        String lower = trimmed.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        if (FORBIDDEN_TAG_KEYS.stream().anyMatch(lower::contains)
                || UUID.matcher(trimmed).matches()
                || LONG_NUMBER.matcher(trimmed).matches()
                || BEARER.matcher(trimmed).matches()
                || JWT.matcher(trimmed).matches()
                || EMAIL.matcher(trimmed).matches()
                || PHONE.matcher(trimmed).matches()
                || ORDER_NO.matcher(trimmed).matches()) {
            return "REDACTED";
        }
        return trimmed.length() > 64 ? "REDACTED" : trimmed;
    }
}
