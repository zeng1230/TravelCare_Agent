package travelcare_agent.human.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import travelcare_agent.enums.HumanReviewCaseStatus;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.packet.HumanHandoffContextPacket;

public final class HumanReviewApprovalPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();

    private HumanReviewApprovalPolicy() { }

    public static boolean allows(HumanReviewCase reviewCase, HumanHandoffContextPacket packet) {
        if (reviewCase == null) return false;
        boolean actionable = reviewCase.getStatus() == HumanReviewCaseStatus.OPEN
                || reviewCase.getStatus() == HumanReviewCaseStatus.ASSIGNED;
        return actionable && hasAuthoritativeApprovalEvidence(packet);
    }

    public static boolean hasAuthoritativeApprovalEvidence(HumanHandoffContextPacket packet) {
        if (packet == null || packet.verifiedOrderFacts() == null
                || packet.refundRuleDecision() == null) return false;
        var order = packet.verifiedOrderFacts();
        var decision = packet.refundRuleDecision();
        boolean authoritativeOrder = order.verified()
                && order.orderId() != null
                && order.orderNo() != null && !order.orderNo().isBlank()
                && order.status() != null && !order.status().isBlank()
                && order.refundable() != null;
        return authoritativeOrder
                && decision.verified()
                && decision.evidenceSufficientForManualDecision()
                && ownershipPassed(decision.policyResultJson());
    }

    private static boolean ownershipPassed(String policyResultJson) {
        if (policyResultJson == null || policyResultJson.isBlank()) return false;
        try {
            JsonNode root = JSON.readTree(policyResultJson);
            return "PASS".equals(root.path("checks").path("ownership").asText(null));
        } catch (Exception ignored) {
            return false;
        }
    }
}
