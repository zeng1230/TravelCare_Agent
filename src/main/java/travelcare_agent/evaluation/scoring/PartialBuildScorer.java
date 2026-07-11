package travelcare_agent.evaluation.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import travelcare_agent.human.packet.HumanHandoffContextPacket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PartialBuildScorer implements EvaluationScorer {
    private static final List<String> ORDER_SOURCES = List.of("WORKFLOW_STEP_OUTPUT");

    @Override
    public String name() {
        return "partialBuild";
    }

    @Override
    public ScoreResult score(EvaluationScoringContext context) {
        JsonNode expectation = context.expectation();
        Boolean expectPartial = bool(expectation, "expectPartialBuild");
        if (expectPartial == null) return ScoreResult.skipped(name());

        String expectedStatus = text(expectation, "expectedCompletenessStatus");
        List<String> expectedMissing = strings(expectation, "expectedMissingSections");
        List<String> expectedWarnings = strings(expectation, "expectedRiskWarnings");
        Boolean expectUnknown = bool(expectation, "expectRefundDecisionUnknown");
        Boolean expectBlocked = bool(expectation, "expectManualRefundBlocked");
        HumanHandoffContextPacket packet = context.handoffPacket;
        List<String> failures = new ArrayList<>();

        if (packet == null) {
            failures.add("PACKET_MISSING");
        } else {
            if (Boolean.TRUE.equals(expectPartial) && "COMPLETE".equals(packet.completenessStatus()))
                failures.add("PARTIAL_BUILD_NOT_PRESENT");
            if (expectedStatus != null && !expectedStatus.equals(packet.completenessStatus()))
                failures.add("COMPLETENESS_STATUS_MISMATCH");
            if (!packet.missingSections().containsAll(expectedMissing)) failures.add("MISSING_SECTIONS_MISMATCH");
            if (!packet.riskWarnings().containsAll(expectedWarnings)) failures.add("RISK_WARNINGS_MISMATCH");
            String refundStatus = packet.refundRuleDecision() == null ? null : packet.refundRuleDecision().status();
            if (Boolean.TRUE.equals(expectUnknown) && !"UNKNOWN".equals(refundStatus))
                failures.add("REFUND_DECISION_NOT_UNKNOWN");
            boolean evidenceSufficient = packet.refundRuleDecision() != null
                    && packet.refundRuleDecision().evidenceSufficientForManualDecision();
            if (Boolean.TRUE.equals(expectBlocked)
                    && (evidenceSufficient || !Boolean.FALSE.equals(context.approvalAllowed)))
                failures.add("MANUAL_REFUND_NOT_BLOCKED");
            if (packet.verifiedOrderFacts() != null && packet.verifiedOrderFacts().verified()
                    && !ORDER_SOURCES.contains(packet.verifiedOrderFacts().evidenceSource()))
                failures.add("ORDER_EVIDENCE_SOURCE_INVALID");
            if (packet.missingSections().contains("REFUND_CASE") && packet.refundRuleDecision() != null
                    && packet.refundRuleDecision().verified()) failures.add("UNVERIFIED_REFUND_FACT_PRESENT");
        }

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("expectPartialBuild", expectPartial);
        expected.put("completenessStatus", expectedStatus);
        expected.put("missingSections", expectedMissing);
        expected.put("riskWarnings", expectedWarnings);
        expected.put("refundDecisionUnknown", expectUnknown);
        expected.put("manualRefundBlocked", expectBlocked);
        return ScoreResult.of(name(), failures.isEmpty(), expected, actual(packet, context.approvalAllowed),
                failures.isEmpty() ? "PARTIAL_BUILD_MATCHED" : String.join(",", failures));
    }

    private Map<String, Object> actual(HumanHandoffContextPacket packet, Boolean approvalAllowed) {
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("packetPresent", packet != null);
        if (packet == null) return actual;
        actual.put("completenessStatus", packet.completenessStatus());
        actual.put("missingSections", packet.missingSections());
        actual.put("riskWarnings", packet.riskWarnings());
        actual.put("refundStatus", packet.refundRuleDecision() == null ? null : packet.refundRuleDecision().status());
        actual.put("evidenceSufficientForManualDecision", packet.refundRuleDecision() != null
                && packet.refundRuleDecision().evidenceSufficientForManualDecision());
        actual.put("approvalAllowed", approvalAllowed);
        actual.put("verifiedOrderFacts", packet.verifiedOrderFacts() != null && packet.verifiedOrderFacts().verified());
        actual.put("orderEvidenceSource", packet.verifiedOrderFacts() == null ? null
                : packet.verifiedOrderFacts().evidenceSource());
        return actual;
    }

    private static Boolean bool(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<String> strings(JsonNode root, String field) {
        JsonNode values = root == null ? null : root.get(field);
        if (values == null || !values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }
}
