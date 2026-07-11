package travelcare_agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import travelcare_agent.evaluation.scoring.EvaluationScoringContext;
import travelcare_agent.evaluation.scoring.PartialBuildScorer;
import travelcare_agent.evaluation.scoring.ScoreResult;
import travelcare_agent.human.packet.HumanHandoffContextPacket;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PartialBuildScorerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void passesForExpectedInsufficientRefundEvidence() {
        EvaluationScoringContext context = context(packet("INSUFFICIENT", "UNKNOWN", false,
                List.of("REFUND_CASE", "REFUND_POLICY_RESULT"),
                List.of("REFUND_DECISION_UNVERIFIED", "MANUAL_REFUND_REQUIRES_VERIFICATION")), false);
        context.expectation = json.valueToTree(java.util.Map.of(
                "expectPartialBuild", true,
                "expectedCompletenessStatus", "INSUFFICIENT",
                "expectedMissingSections", List.of("REFUND_CASE"),
                "expectedRiskWarnings", List.of("REFUND_DECISION_UNVERIFIED"),
                "expectRefundDecisionUnknown", true,
                "expectManualRefundBlocked", true));

        ScoreResult result = new PartialBuildScorer().score(context);

        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).isEqualTo("PARTIAL_BUILD_MATCHED");
    }

    @Test
    void failsWhenMissingRefundEvidenceStillReturnsVerifiedDecision() {
        EvaluationScoringContext context = context(packet("INSUFFICIENT", "ELIGIBLE", true,
                List.of("REFUND_CASE"), List.of("REFUND_DECISION_UNVERIFIED")), true);
        context.expectation = json.valueToTree(java.util.Map.of(
                "expectPartialBuild", true,
                "expectedCompletenessStatus", "INSUFFICIENT",
                "expectRefundDecisionUnknown", true,
                "expectManualRefundBlocked", true));

        ScoreResult result = new PartialBuildScorer().score(context);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("REFUND_DECISION_NOT_UNKNOWN", "MANUAL_REFUND_NOT_BLOCKED");
    }

    @Test
    void skipsWhenExpectationIsNotConfigured() {
        EvaluationScoringContext context = context(packet("COMPLETE", "ELIGIBLE", true, List.of(), List.of()), true);
        context.expectation = json.createObjectNode();

        assertThat(new PartialBuildScorer().score(context).applied()).isFalse();
    }

    private EvaluationScoringContext context(HumanHandoffContextPacket packet, boolean approvalAllowed) {
        EvaluationScoringContext context = new EvaluationScoringContext();
        context.handoffPacket = packet;
        context.approvalAllowed = approvalAllowed;
        return context;
    }

    private HumanHandoffContextPacket packet(String completeness, String refundStatus, boolean verified,
            List<String> missing, List<String> warnings) {
        return new HumanHandoffContextPacket("PR-4D-v1", "MATERIALIZED", 1L, 10L, 20L, 30L,
                new HumanHandoffContextPacket.CustomerGoal("refund", "REFUND_INQUIRY", null, null,
                        "refund approved", List.of(), "UNVERIFIED_CONVERSATION_CONTEXT"),
                new HumanHandoffContextPacket.VerifiedOrderFacts(null, null, null, null, false, null),
                new HumanHandoffContextPacket.RefundRuleDecision(30L, refundStatus, null, null, null,
                        verified, verified),
                new HumanHandoffContextPacket.RagEvidence(List.of(), List.of()), List.of(),
                new HumanHandoffContextPacket.SupplierGatewaySummary(false, false, null,
                        "NOT_APPLICABLE", false),
                new HumanHandoffContextPacket.SafetyDecisionSummary("UNKNOWN", "EVIDENCE_UNAVAILABLE", List.of()),
                new HumanHandoffContextPacket.HandoffReason("NEED_HUMAN", "manual"),
                new HumanHandoffContextPacket.RecommendedNextSteps("HIGH", List.of(), List.of()), List.of(),
                completeness, missing, warnings,
                new HumanHandoffContextPacket.AnswerabilitySummary("UNKNOWN", "EVIDENCE_UNAVAILABLE"));
    }
}
