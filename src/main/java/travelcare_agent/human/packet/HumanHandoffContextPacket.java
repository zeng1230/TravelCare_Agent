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
        List<String> warnings,
        String completenessStatus,
        List<String> missingSections,
        List<String> riskWarnings,
        AnswerabilitySummary answerability
) {
    public HumanHandoffContextPacket(
            String packetVersion, String packetMode, Long caseId, Long sessionId, Long workflowId, Long refundCaseId,
            CustomerGoal customerGoal, VerifiedOrderFacts verifiedOrderFacts, RefundRuleDecision refundRuleDecision,
            RagEvidence ragEvidence, List<ToolCallSummary> toolCalls, SupplierGatewaySummary supplierGateway,
            SafetyDecisionSummary safetyDecision, HandoffReason handoffReason,
            RecommendedNextSteps recommendedNextSteps, List<String> warnings) {
        this(packetVersion, packetMode, caseId, sessionId, workflowId, refundCaseId, customerGoal,
                verifiedOrderFacts, refundRuleDecision, ragEvidence, toolCalls, supplierGateway, safetyDecision,
                handoffReason, recommendedNextSteps, warnings, "COMPLETE", List.of(), List.of(),
                new AnswerabilitySummary("UNKNOWN", "EVIDENCE_UNAVAILABLE"));
    }

    public record CustomerGoal(
            String summary,
            String intent,
            String orderNo,
            Long userId,
            String latestUserMessage,
            List<MessageSummary> recentMessages,
            String contextType
    ) {
        public CustomerGoal(String summary, String intent, String orderNo, Long userId, String latestUserMessage,
                List<MessageSummary> recentMessages) {
            this(summary, intent, orderNo, userId, latestUserMessage, recentMessages,
                    "UNVERIFIED_CONVERSATION_CONTEXT");
        }
    }

    public record MessageSummary(String role, String content, String createdAt) {
    }

    public record VerifiedOrderFacts(Long orderId, String orderNo, String status, Boolean refundable,
                                     boolean verified, String evidenceSource) {
        public VerifiedOrderFacts(Long orderId, String orderNo, String status, Boolean refundable) {
            this(orderId, orderNo, status, refundable, orderNo != null, orderNo == null ? null : "WORKFLOW_STEP_OUTPUT");
        }
    }

    public record RefundRuleDecision(
            Long refundCaseId,
            String status,
            BigDecimal refundAmount,
            String reason,
            String policyResultJson,
            boolean verified,
            boolean evidenceSufficientForManualDecision
    ) {
        public RefundRuleDecision(Long refundCaseId, String status, BigDecimal refundAmount, String reason,
                String policyResultJson) {
            this(refundCaseId, status, refundAmount, reason, policyResultJson,
                    refundCaseId != null && status != null && policyResultJson != null,
                    refundCaseId != null && status != null && policyResultJson != null);
        }
    }

    public record AnswerabilitySummary(String status, String reasonCode) {
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

    public record SupplierGatewaySummary(boolean participated, boolean failed, String failureCode,
                                         String status, boolean verified) {
        public SupplierGatewaySummary(boolean participated, boolean failed, String failureCode) {
            this(participated, failed, failureCode,
                    participated ? (failed ? "FAILED" : "AVAILABLE") : "NOT_APPLICABLE", participated);
        }
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
