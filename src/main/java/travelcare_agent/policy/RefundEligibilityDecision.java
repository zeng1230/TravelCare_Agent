package travelcare_agent.policy;

import travelcare_agent.enums.RefundCaseStatus;

import java.math.BigDecimal;

public record RefundEligibilityDecision(
        RefundCaseStatus status,
        String reason,
        BigDecimal refundAmount,
        String policyResultJson
) {
}
