package travelcare_agent.human.packet;

import java.math.BigDecimal;
import java.util.List;

public record HumanHandoffContextPacket(
        String packetVersion,
        String packetMode,
        Long caseId,
        Long sessionId,
        Long workflowId,
        Long refundCaseId,
        CustomerGoal customerGoal,
        VerifiedOrderFacts verifiedOrderFacts,
        RefundRuleDecision refundRuleDecision,
        RagEvidence ragEvidence,
        List<ToolCallSummary> toolCalls,
        SupplierGatewaySummary supplierGateway,
        SafetyDecisionSummary safetyDecision,
        HandoffReason handoffReason,
        RecommendedNextSteps recommendedNextSteps,
        List<String> warnings
) {
    public record CustomerGoal(
            String summary,
            String intent,
            String orderNo,
            Long userId,
            String latestUserMessage,
            List<MessageSummary> recentMessages
    ) {
    }

    public record MessageSummary(String role, String content, String createdAt) {
    }

    public record VerifiedOrderFacts(Long orderId, String orderNo, String status, Boolean refundable) {
    }

    public record RefundRuleDecision(
            Long refundCaseId,
            String status,
            BigDecimal refundAmount,
            String reason,
            String policyResultJson
    ) {
    }

    public record RagEvidence(
            List<CitationSummary> acceptedCitations,
            List<CitationSummary> rejectedCitations
    ) {
    }

    public record CitationSummary(
            String retrievalRunId,
            Long documentId,
            Long chunkId,
            String title,
            String sourceUri,
            String rejectionReason
    ) {
    }

    public record ToolCallSummary(String name, String status, String errorCode, String outputRef) {
    }

    public record SupplierGatewaySummary(boolean participated, boolean failed, String failureCode) {
    }

    public record SafetyDecisionSummary(String decision, String reasonCode, List<String> riskTags) {
    }

    public record HandoffReason(String reasonCode, String explanation) {
    }

    public record RecommendedNextSteps(String priority, List<RecommendedStep> steps, List<String> doNotDo) {
    }

    public record RecommendedStep(String action, String label, String reason) {
    }
}
