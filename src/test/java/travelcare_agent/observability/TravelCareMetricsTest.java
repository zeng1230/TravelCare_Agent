package travelcare_agent.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TravelCareMetricsTest {

    @Test
    void countersUseTotalSuffixAndTimersRecordDuration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TravelCareMetrics metrics = new TravelCareMetrics(registry);

        metrics.workflowStarted("order_refund_inquiry", "COLLECTING_ORDER_REFERENCE");
        metrics.workflowCompleted("order_refund_inquiry", "RESPONDED", "RESPONDED", Duration.ofMillis(25));

        assertThat(registry.get("travelcare.workflow.started.total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("travelcare.workflow.completed.total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("travelcare.workflow.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("travelcare.workflow.duration").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isGreaterThanOrEqualTo(25.0);
    }

    @Test
    void llmModelTagIsNormalizedToWhitelistOrUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TravelCareMetrics metrics = new TravelCareMetrics(registry);

        metrics.llmSuccess("deepseek", "provider/deployments/request-1234567890/deepseek-chat", "deepseek",
                "ALLOW", Duration.ofMillis(10), 3, 4);
        metrics.llmSuccess("mock", "mock-stage10a", "mock", "ALLOW", Duration.ofMillis(1), 0, 0);

        assertThat(registry.find("travelcare.llm.success.total").tag("model", "unknown").counter()).isNotNull();
        assertThat(registry.find("travelcare.llm.success.total").tag("model", "mock-stage10a").counter()).isNotNull();
    }

    @Test
    void tagSanitizerRejectsSensitiveAndHighCardinalityValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TravelCareMetrics metrics = new TravelCareMetrics(registry);

        metrics.counter("travelcare.test.sanitized.total", Map.of(
                "traceId", "52a47efb-4f68-4974-bfc9-1fb87df1b8a6",
                "failureCode", "Bearer eyJabc.def.ghi",
                "toolName", "GetOrderTool",
                "orderNo", "ORD-12345"
        ));

        var id = registry.get("travelcare.test.sanitized.total").meter().getId();
        assertThat(id.getTags()).noneSatisfy(tag -> {
            assertThat(tag.getKey()).containsIgnoringCase("traceId");
        });
        assertThat(id.getTag("failureCode")).isEqualTo("REDACTED");
        assertThat(id.getTag("toolName")).isEqualTo("GetOrderTool");
        assertThat(id.getTag("orderNo")).isNull();
    }

    @Test
    void recordsOperationalBoundaryMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TravelCareMetrics metrics = new TravelCareMetrics(registry);
        AtomicLong backlog = new AtomicLong(2);
        AtomicLong pending = new AtomicLong(3);

        metrics.toolStarted("GetOrderTool", false);
        metrics.toolCompleted("GetOrderTool", false, Duration.ofMillis(5));
        metrics.toolFailed("GetOrderTool", false, "SYSTEM_ERROR", Duration.ofMillis(6));
        metrics.toolUnknown("GetOrderTool", true, "UNKNOWN", Duration.ofMillis(7));
        metrics.toolSkipped("GetOrderTool", false, "IDEMPOTENT_REUSE");
        metrics.toolRetry("GetOrderTool", true, "TRANSIENT_ERROR");

        metrics.llmRequest("mock", "mock-stage10a", "mock");
        metrics.llmSuccess("mock", "mock-stage10a", "mock", "ALLOW", Duration.ofMillis(8), 10, 11);
        metrics.llmFailure("mock", "mock-stage10a", "mock", "PROVIDER_ERROR", Duration.ofMillis(9), 12, 0);
        metrics.llmFallback("mock", "mock-stage10a", "mock", "PROVIDER_ERROR");
        metrics.llmSafetyBlock("mock", "mock-stage10a", "mock", "BLOCK", "UNSAFE_CONTENT");

        metrics.safetyDecision("ALLOW", "NONE", "mock");
        metrics.safetyDecision("BLOCK", "UNSAFE_CONTENT", "mock");
        metrics.safetyDecision("FALLBACK", "LOW_CONFIDENCE", "mock");

        metrics.outboxCreated("WORKFLOW_TASK");
        metrics.outboxPublished("WORKFLOW_TASK", Duration.ofMillis(10));
        metrics.outboxRetry("WORKFLOW_TASK", "CONFIRM_TIMEOUT");
        metrics.outboxFailed("WORKFLOW_TASK", "MAX_ATTEMPTS");
        metrics.gauge("travelcare.outbox.backlog", backlog::get);

        metrics.workerStarted("WORKFLOW_TASK");
        metrics.workerSucceeded("WORKFLOW_TASK");
        metrics.workerFailed("WORKFLOW_TASK", "SYSTEM_ERROR");
        metrics.workerRetryScheduled("WORKFLOW_TASK", "SYSTEM_ERROR");
        metrics.workerSkipped("WORKFLOW_TASK", "NOT_DUE");
        metrics.workerDeadLettered("WORKFLOW_TASK", "MAX_ATTEMPTS");

        metrics.reconciliationCreated("TOOL_CALL", "UNKNOWN_TOOL_RESULT");
        metrics.reconciliationResolved("TOOL_CALL", "CONFIRMED_SUCCESS", "MATCHED");
        metrics.reconciliationResolved("TOOL_CALL", "CONFIRMED_FAILED", "MISMATCH");
        metrics.reconciliationResolved("TOOL_CALL", "UNKNOWN", "UNKNOWN");
        metrics.gauge("travelcare.reconciliation.pending", pending::get);

        assertCounter(registry, "travelcare.tool.call.unknown.total", 1.0);
        assertCounter(registry, "travelcare.tool.call.skipped.total", 1.0);
        assertCounter(registry, "travelcare.tool.call.retry.total", 1.0);
        assertThat(timerCount(registry, "travelcare.tool.call.duration")).isEqualTo(3);

        assertCounter(registry, "travelcare.llm.fallback.total", 1.0);
        assertCounter(registry, "travelcare.llm.safety_block.total", 1.0);
        assertThat(timerCount(registry, "travelcare.llm.latency")).isEqualTo(2);
        assertThat(summaryCount(registry, "travelcare.llm.input_tokens")).isEqualTo(2);

        assertCounter(registry, "travelcare.safety.allowed.total", 1.0);
        assertCounter(registry, "travelcare.safety.blocked.total", 1.0);
        assertCounter(registry, "travelcare.safety.fallback.total", 1.0);

        assertCounter(registry, "travelcare.outbox.event.created.total", 1.0);
        assertCounter(registry, "travelcare.outbox.event.published.total", 1.0);
        assertCounter(registry, "travelcare.outbox.event.retry.total", 1.0);
        assertCounter(registry, "travelcare.outbox.event.failed.total", 1.0);
        assertThat(registry.get("travelcare.outbox.publish.latency").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isGreaterThanOrEqualTo(10.0);
        assertThat(registry.get("travelcare.outbox.backlog").gauge().value()).isEqualTo(2.0);

        assertCounter(registry, "travelcare.worker.task.retry_scheduled.total", 1.0);
        assertCounter(registry, "travelcare.worker.task.skipped.total", 1.0);
        assertCounter(registry, "travelcare.worker.task.dead_lettered.total", 1.0);

        assertCounter(registry, "travelcare.reconciliation.created.total", 1.0);
        assertCounter(registry, "travelcare.reconciliation.resolved_success.total", 1.0);
        assertCounter(registry, "travelcare.reconciliation.resolved_failed.total", 1.0);
        assertCounter(registry, "travelcare.reconciliation.unknown.total", 1.0);
        assertThat(registry.get("travelcare.reconciliation.pending").gauge().value()).isEqualTo(3.0);
    }

    private static void assertCounter(SimpleMeterRegistry registry, String name, double count) {
        assertThat(registry.get(name).counter().count()).isEqualTo(count);
    }

    private static long timerCount(SimpleMeterRegistry registry, String name) {
        return registry.find(name).timers().stream().mapToLong(io.micrometer.core.instrument.Timer::count).sum();
    }

    private static long summaryCount(SimpleMeterRegistry registry, String name) {
        return registry.find(name).summaries().stream().mapToLong(io.micrometer.core.instrument.DistributionSummary::count).sum();
    }
}
