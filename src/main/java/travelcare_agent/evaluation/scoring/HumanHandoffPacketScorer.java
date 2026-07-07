package travelcare_agent.evaluation.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import travelcare_agent.evaluation.EvaluationLeakageSanitizer;
import travelcare_agent.human.packet.HumanHandoffContextPacket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HumanHandoffPacketScorer implements EvaluationScorer {
    public String name() {
        return "humanHandoffPacket";
    }

    public ScoreResult score(EvaluationScoringContext c) {
        JsonNode e = c.expectation();
        Boolean expectComplete = bool(e, "expectHumanHandoffPacketComplete");
        String expectedReason = text(e, "expectedHandoffReasonCode");
        if (expectComplete == null && expectedReason == null) return ScoreResult.skipped(name());
        HumanHandoffContextPacket packet = c.handoffPacket;
        List<String> failures = new ArrayList<>();
        if (packet == null) {
            failures.add("handoff packet missing");
        } else {
            if (Boolean.TRUE.equals(expectComplete)) failures.addAll(completenessFailures(packet));
            if (expectedReason != null && (packet.handoffReason() == null
                    || !expectedReason.equals(packet.handoffReason().reasonCode()))) {
                failures.add("handoffReason mismatch");
            }
            if (packetContainsSensitiveLeakage(packet)) {
                failures.add("sensitive data found in packet");
            }
        }
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("complete", expectComplete);
        expected.put("handoffReasonCode", expectedReason);
        return ScoreResult.of(name(), failures.isEmpty(), expected, actual(packet),
                failures.isEmpty() ? "human handoff packet matched" : "human handoff packet mismatch: " + failures);
    }

    private List<String> completenessFailures(HumanHandoffContextPacket p) {
        List<String> failures = new ArrayList<>();
        if (p.customerGoal() == null || blank(p.customerGoal().summary())) failures.add("customerGoal missing");
        if (p.verifiedOrderFacts() == null || blank(p.verifiedOrderFacts().orderNo()))
            failures.add("verifiedOrderFacts missing");
        if (p.refundRuleDecision() == null || blank(p.refundRuleDecision().status()))
            failures.add("refundRuleDecision missing");
        if (p.ragEvidence() == null
                || (p.ragEvidence().acceptedCitations().isEmpty() && p.ragEvidence().rejectedCitations().isEmpty()))
            failures.add("ragEvidence missing");
        if (p.handoffReason() == null || blank(p.handoffReason().reasonCode())) failures.add("handoffReason missing");
        if (p.recommendedNextSteps() == null || p.recommendedNextSteps().steps().isEmpty())
            failures.add("recommendedNextSteps missing");
        return failures;
    }

    private Map<String, Object> actual(HumanHandoffContextPacket p) {
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("present", p != null);
        if (p == null) return actual;
        actual.put("packetVersion", p.packetVersion());
        actual.put("packetMode", p.packetMode());
        actual.put("orderNo", p.verifiedOrderFacts() == null ? null : p.verifiedOrderFacts().orderNo());
        actual.put("refundStatus", p.refundRuleDecision() == null ? null : p.refundRuleDecision().status());
        actual.put("acceptedCitationCount", p.ragEvidence() == null ? 0 : p.ragEvidence().acceptedCitations().size());
        actual.put("rejectedCitationCount", p.ragEvidence() == null ? 0 : p.ragEvidence().rejectedCitations().size());
        actual.put("supplierFailureCode", p.supplierGateway() == null ? null : p.supplierGateway().failureCode());
        actual.put("safetyDecision", p.safetyDecision() == null ? null : p.safetyDecision().decision());
        actual.put("handoffReasonCode", p.handoffReason() == null ? null : p.handoffReason().reasonCode());
        actual.put("sensitiveLeakageDetected", packetContainsSensitiveLeakage(p));
        return actual;
    }

    private boolean packetContainsSensitiveLeakage(HumanHandoffContextPacket p) {
        List<String> values = new ArrayList<>();
        if (p.customerGoal() != null) {
            values.add(p.customerGoal().summary());
            values.add(p.customerGoal().intent());
            values.add(p.customerGoal().orderNo());
            values.add(p.customerGoal().latestUserMessage());
            p.customerGoal().recentMessages().forEach(message -> {
                values.add(message.role());
                values.add(message.content());
            });
        }
        if (p.refundRuleDecision() != null) {
            values.add(p.refundRuleDecision().status());
            values.add(p.refundRuleDecision().reason());
            values.add(p.refundRuleDecision().policyResultJson());
        }
        if (p.ragEvidence() != null) {
            p.ragEvidence().acceptedCitations().forEach(citation -> addCitation(values, citation));
            p.ragEvidence().rejectedCitations().forEach(citation -> addCitation(values, citation));
        }
        if (p.toolCalls() != null) {
            p.toolCalls().forEach(tool -> {
                values.add(tool.name());
                values.add(tool.status());
                values.add(tool.errorCode());
                values.add(tool.outputRef());
            });
        }
        if (p.supplierGateway() != null) values.add(p.supplierGateway().failureCode());
        if (p.safetyDecision() != null) {
            values.add(p.safetyDecision().decision());
            values.add(p.safetyDecision().reasonCode());
            values.addAll(p.safetyDecision().riskTags());
        }
        if (p.handoffReason() != null) {
            values.add(p.handoffReason().reasonCode());
            values.add(p.handoffReason().explanation());
        }
        if (p.recommendedNextSteps() != null) {
            values.add(p.recommendedNextSteps().priority());
            p.recommendedNextSteps().steps().forEach(step -> {
                values.add(step.action());
                values.add(step.label());
                values.add(step.reason());
            });
            values.addAll(p.recommendedNextSteps().doNotDo());
        }
        if (p.warnings() != null) values.addAll(p.warnings());
        return EvaluationLeakageSanitizer.containsSensitiveLeakage(String.join("\n",
                values.stream().filter(value -> value != null && !value.isBlank()).toList()));
    }

    private void addCitation(List<String> values, HumanHandoffContextPacket.CitationSummary citation) {
        values.add(citation.retrievalRunId());
        values.add(citation.title());
        values.add(citation.sourceUri());
        values.add(citation.rejectionReason());
    }

    private static Boolean bool(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
