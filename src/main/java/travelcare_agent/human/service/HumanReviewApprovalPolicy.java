package travelcare_agent.human.service;

import travelcare_agent.enums.HumanReviewCaseStatus;
import travelcare_agent.human.entity.HumanReviewCase;
import travelcare_agent.human.packet.HumanHandoffContextPacket;

public final class HumanReviewApprovalPolicy {
    private HumanReviewApprovalPolicy() { }

    public static boolean allows(HumanReviewCase reviewCase, HumanHandoffContextPacket packet) {
        if (reviewCase == null || packet == null || packet.refundRuleDecision() == null) return false;
        boolean actionable = reviewCase.getStatus() == HumanReviewCaseStatus.OPEN
                || reviewCase.getStatus() == HumanReviewCaseStatus.ASSIGNED;
        return actionable && packet.refundRuleDecision().evidenceSufficientForManualDecision();
    }
}
