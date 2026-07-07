package travelcare_agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import travelcare_agent.evaluation.scoring.*;
import travelcare_agent.human.packet.HumanHandoffContextPacket;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Pr3cEvaluationScorersTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void safetyDecisionScorerOnlyChecksConfiguredFields() throws Exception {
        EvaluationScoringContext context = EvaluationScoringContext.builder()
                .expectation(json.readTree("{\"expectedSafetyDecision\":\"BLOCK\"}"))
                .build();
        context.safetyDecision = "BLOCK";
        context.safetyReasonCode = "AUTHORITATIVE_DECISION_CONFLICT";
        context.safetyRiskFlags = List.of("REFUND_CONFLICT");

        ScoreResult result = new SafetyDecisionScorer().score(context);

        assertThat(result.applied()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void safetyDecisionScorerFailsWhenConfiguredFieldCannotBeObserved() throws Exception {
        EvaluationScoringContext context = EvaluationScoringContext.builder()
                .expectation(json.readTree("{\"expectedSafetyDecision\":\"BLOCK\"}"))
                .build();

        ScoreResult result = new SafetyDecisionScorer().score(context);

        assertThat(result.applied()).isTrue();
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("safetyDecision missing");
    }

    @Test
    void supplierFailureScorerChecksOnlyConfiguredFields() throws Exception {
        EvaluationScoringContext context = EvaluationScoringContext.builder()
                .expectation(json.readTree("{\"expectedSupplierFailureCode\":\"SUPPLIER_TIMEOUT\"}"))
                .build();
        context.supplierGatewayParticipated = false;
        context.supplierFailureCode = "SUPPLIER_TIMEOUT";

        ScoreResult result = new SupplierFailureClassificationScorer().score(context);

        assertThat(result.applied()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void providerFallbackScorerRejectsRawPromptAndProviderOutputLeakage() throws Exception {
        EvaluationScoringContext context = EvaluationScoringContext.builder()
                .expectation(json.readTree("""
                        {"expectedProviderFallbackUsed":true,"forbidRawPromptOrProviderOutput":true}
                        """))
                .build();
        context.providerFallbackUsed = true;
        context.leakageCheckText = "hash ok\nAuthorization: Bearer secret-token\nraw_provider_output={bad json}";

        ScoreResult result = new ProviderFallbackScorer().score(context);

        assertThat(result.applied()).isTrue();
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("leakage");
    }

    @Test
    void humanHandoffPacketScorerRequiresCompleteDesensitizedPacket() throws Exception {
        EvaluationScoringContext context = EvaluationScoringContext.builder()
                .expectation(json.readTree("""
                        {"expectHumanHandoffPacketComplete":true,"expectedHandoffReasonCode":"ORDER_LOOKUP_FAILED"}
                        """))
                .build();
        context.handoffPacket = packet("ORDER_LOOKUP_FAILED", "https://example.com/sop?ok=1", "phone=[REDACTED]");

        ScoreResult result = new HumanHandoffPacketScorer().score(context);

        assertThat(result.applied()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void humanHandoffPacketScorerFailsWhenPacketContainsSensitiveData() throws Exception {
        EvaluationScoringContext context = EvaluationScoringContext.builder()
                .expectation(json.readTree("{\"expectHumanHandoffPacketComplete\":true}"))
                .build();
        context.handoffPacket = packet("ORDER_LOOKUP_FAILED", "https://example.com/sop?token=secret", "phone=13812345678");

        ScoreResult result = new HumanHandoffPacketScorer().score(context);

        assertThat(result.applied()).isTrue();
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("sensitive data");
    }

    private HumanHandoffContextPacket packet(String reasonCode, String sourceUri, String latestUserMessage) {
        return new HumanHandoffContextPacket(
                "PR-3A-v1",
                "MATERIALIZED",
                90L,
                10L,
                20L,
                30L,
                new HumanHandoffContextPacket.CustomerGoal(
                        "Customer wants refund help.",
                        "REFUND_INQUIRY",
                        "ORD-1001",
                        null,
                        latestUserMessage,
                        List.of(new HumanHandoffContextPacket.MessageSummary("USER", latestUserMessage, "2026-06-15T00:00:00"))
                ),
                new HumanHandoffContextPacket.VerifiedOrderFacts(1001L, "ORD-1001", "PAID", true),
                new HumanHandoffContextPacket.RefundRuleDecision(
                        30L, "NEED_HUMAN", new BigDecimal("188.00"),
                        "manual review", "{\"decision\":\"NEED_HUMAN\"}"
                ),
                new HumanHandoffContextPacket.RagEvidence(
                        List.of(new HumanHandoffContextPacket.CitationSummary(
                                "ret-1", 7L, 8L, "Refund SOP", sourceUri, null)),
                        List.of(new HumanHandoffContextPacket.CitationSummary(
                                "ret-1", 9L, 10L, "Old SOP", "https://example.com/old", "EXPIRED_SOURCE"))
                ),
                List.of(new HumanHandoffContextPacket.ToolCallSummary("GetOrderTool", "FAILED", "SUPPLIER_TIMEOUT", "TOOL_CALL:501")),
                new HumanHandoffContextPacket.SupplierGatewaySummary(true, true, "SUPPLIER_TIMEOUT"),
                new HumanHandoffContextPacket.SafetyDecisionSummary("HANDOFF", "AUTHORITATIVE_DECISION_CONFLICT", List.of("REFUND_CONFLICT")),
                new HumanHandoffContextPacket.HandoffReason(reasonCode, "Supplier lookup failed."),
                new HumanHandoffContextPacket.RecommendedNextSteps(
                        "HIGH",
                        List.of(new HumanHandoffContextPacket.RecommendedStep(
                                "CHECK_SUPPLIER_STATUS", "Check Supplier Gateway health.", "Supplier lookup failed.")),
                        List.of("Do not promise a refund until verified.")),
                List.of()
        );
    }
}
