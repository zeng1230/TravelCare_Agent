package travelcare_agent.answerability;

public record BusinessDecisionContext(
        boolean businessDecisionLocked,
        String workflowStatus,
        String refundDecision,
        String orderStatus,
        String refundAmount
) {
    public static BusinessDecisionContext none() {
        return new BusinessDecisionContext(false, null, null, null, null);
    }
}
