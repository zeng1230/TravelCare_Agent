package travelcare_agent.dryrun;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDiffServiceTest {
    @Test
    void policyChangeIsHighRisk() {
        TraceDiffResult result = TraceDiffService.compare(
                Map.of("finalAnswer", "eligible", "policyDecision", "ELIGIBLE", "workflowPath", "RESPONDED"),
                Map.of("finalAnswer", "not eligible", "policyDecision", "INELIGIBLE", "workflowPath", "RESPONDED")
        );
        assertThat(result.changed()).isTrue();
        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.changedFields()).extracting(TraceDiffResult.ChangedField::field).contains("policyDecision");
    }

    @Test
    void wordingOnlyChangeIsLowRisk() {
        TraceDiffResult result = TraceDiffService.compare(
                Map.of("finalAnswer", "answer one", "policyDecision", "ELIGIBLE"),
                Map.of("finalAnswer", "answer two", "policyDecision", "ELIGIBLE")
        );
        assertThat(result.riskLevel()).isEqualTo("LOW");
    }

    @Test
    void wrapperTextAndEquivalentMoneyFormattingStayLowRisk() {
        TraceDiffResult result = TraceDiffService.compare(
                Map.of(
                        "finalAnswer", "Order query recognized for ORD-1001. Workflow result: Order ORD-1001 is eligible for refund inquiry. Refund amount can be reviewed up to 100.00.",
                        "policyDecision", Map.of("decision", "ELIGIBLE", "refundAmount", new BigDecimal("100.00")),
                        "toolCallSummary", toolResult("100.00")
                ),
                Map.of(
                        "finalAnswer", "Order ORD-1001 is eligible for refund inquiry. Refund amount can be reviewed up to 100.0.",
                        "policyDecision", Map.of("decision", "ELIGIBLE", "refundAmount", new BigDecimal("100.0")),
                        "toolCallSummary", toolResult("100.0")
                )
        );

        assertThat(result.riskLevel()).isIn("NONE", "LOW");
    }

    @Test
    void expectedSpanDistributionDifferenceIsAtMostMediumRisk() {
        TraceDiffResult result = TraceDiffService.compare(
                stableBusinessSummary(Map.of("AUDIT:SUCCEEDED", 2L, "CONTEXT:SUCCEEDED", 1L, "OUTPUT:SUCCEEDED", 1L), List.of()),
                stableBusinessSummary(Map.of("REQUEST:SUCCEEDED", 1L, "MODEL:SUCCEEDED", 1L), List.of())
        );

        assertThat(result.riskLevel()).isIn("LOW", "MEDIUM");
    }

    @Test
    void retrievalFallbackDifferenceWithoutBusinessChangeIsAtMostMediumRisk() {
        TraceDiffResult result = TraceDiffService.compare(
                stableBusinessSummary(Map.of(), List.of("FALLBACK:fulltext-to-like")),
                stableBusinessSummary(Map.of(), List.of())
        );

        assertThat(result.riskLevel()).isIn("LOW", "MEDIUM");
    }

    @Test
    void unchangedBusinessConclusionNeverBecomesHighRiskFromDiagnosticDifferences() {
        Map<String, Object> original = stableBusinessSummary(
                Map.of("AUDIT:SUCCEEDED", 2L, "CONTEXT:SUCCEEDED", 1L),
                List.of("FALLBACK:fulltext-to-like")
        );
        original.put("workflowPath", List.of(
                Map.of("name", "QUERYING_ORDER", "status", "SUCCESS"),
                Map.of("name", "CHECKING_REFUND_RULES", "status", "SUCCESS")
        ));
        original.put("modelOutputSummary", Map.of("output", Map.of("answer", "Refund amount 100.00"), "provider", "mock"));

        Map<String, Object> dryRun = stableBusinessSummary(Map.of("MODEL:SUCCEEDED", 1L), List.of());
        dryRun.put("workflowPath", List.of(
                Map.of("status", "SUCCEEDED", "name", "QUERYING_ORDER"),
                Map.of("status", "SUCCEEDED", "name", "CHECKING_REFUND_RULES")
        ));
        dryRun.put("modelOutputSummary", Map.of("provider", "mock", "output", Map.of("answer", "Refund amount 100.0")));

        TraceDiffResult result = TraceDiffService.compare(original, dryRun);

        assertThat(result.riskLevel()).isNotEqualTo("HIGH");
        assertThat(result.explanation()).startsWith("Diagnostic comparison").endsWith(".");
        assertThat(result.explanation()).contains("business conclusion");
    }

    private static Map<String, Object> stableBusinessSummary(Map<String, Long> spans, List<String> events) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("finalAnswer", "Order ORD-1001 is eligible for refund inquiry. Refund amount can be reviewed up to 100.00.");
        summary.put("policyDecision", Map.of("decision", "ELIGIBLE", "refundAmount", new BigDecimal("100.00")));
        summary.put("toolCallSummary", toolResult("100.00"));
        summary.put("spanStatusDistribution", spans);
        summary.put("specialEvents", events);
        return summary;
    }

    private static Map<String, Object> toolResult(String amount) {
        return Map.of("toolName", "GetOrderTool", "status", "SUCCESS", "result", Map.of(
                "orderNo", "ORD-1001", "status", "PAID", "refundable", true,
                "paidAmount", new BigDecimal(amount), "departureTime", "2026-06-18T10:00:00"
        ));
    }
}
