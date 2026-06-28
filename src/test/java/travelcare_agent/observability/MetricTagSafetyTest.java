package travelcare_agent.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MetricTagSafetyTest {

    private static final Pattern UUID = Pattern.compile("(?i).*\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b.*");
    private static final Pattern LONG_NUMBER = Pattern.compile(".*\\b\\d{8,}\\b.*");
    private static final Pattern BEARER = Pattern.compile("(?i).*Bearer\\s+[A-Za-z0-9._~+/-]+=*.*");
    private static final Pattern JWT = Pattern.compile(".*eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+.*");
    private static final Pattern EMAIL = Pattern.compile("(?i).*[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}.*");
    private static final Pattern PHONE = Pattern.compile(".*(?<!\\d)1[3-9]\\d{9}(?!\\d).*");
    private static final Pattern ORDER_NO = Pattern.compile("(?i).*\\bORD[-_]?\\d+\\b.*");

    @Test
    void metricTagKeysAndValuesDoNotExposeSensitiveOrHighCardinalityData() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TravelCareMetrics metrics = new TravelCareMetrics(registry);

        metrics.workflowStarted("order_refund_inquiry", "COLLECTING_ORDER_REFERENCE");
        metrics.toolUnknown("RefundTool", true, "TOOL_TIMEOUT", Duration.ofMillis(5));
        metrics.llmFailure("deepseek", "deployment/abc/request/1234567890", "real", "MODEL_TIMEOUT",
                Duration.ofMillis(5), null, null);
        metrics.safetyDecision("BLOCK", "UNSAFE_COMMITMENT", "mock");

        for (Meter meter : registry.getMeters()) {
            for (Meter.Id id : java.util.List.of(meter.getId())) {
                id.getTags().forEach(tag -> {
                    assertSafeText(tag.getKey());
                    assertSafeText(tag.getValue());
                    assertThat(UUID.matcher(tag.getValue()).matches()).isFalse();
                    assertThat(LONG_NUMBER.matcher(tag.getValue()).matches()).isFalse();
                    assertThat(BEARER.matcher(tag.getValue()).matches()).isFalse();
                    assertThat(JWT.matcher(tag.getValue()).matches()).isFalse();
                    assertThat(EMAIL.matcher(tag.getValue()).matches()).isFalse();
                    assertThat(PHONE.matcher(tag.getValue()).matches()).isFalse();
                    assertThat(ORDER_NO.matcher(tag.getValue()).matches()).isFalse();
                });
            }
        }
    }

    private static void assertSafeText(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain(
                "userid", "tenantid", "sessionid", "workflowid", "orderno",
                "traceid", "prompt", "token", "secret"
        );
    }
}
