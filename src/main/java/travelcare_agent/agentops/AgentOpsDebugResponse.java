package travelcare_agent.agentops;

import java.util.List;

public record AgentOpsDebugResponse(
        Long sessionId,
        Long workflowId,
        String traceId,
        String debugMode,
        DebugEvidenceMode evidenceMode,
        String providerMode,
        String modelProvider,
        String promptVersion,
        String question,
        RetrievalDebug retrieval,
        AnswerabilityDebug answerability,
        SafetyDebug safety,
        SupplierGatewayDebug supplierGateway,
        List<ToolCallDebug> toolCalls,
        DebugFinalRoute finalRoute,
        HumanHandoffRecommendation humanHandoffRecommendation,
        List<String> diagnosticWarnings,
        String completenessStatus,
        List<String> missingSections,
        List<String> riskWarnings,
        DiagnosticDebug diagnostic
) {
    public AgentOpsDebugResponse(Long sessionId, Long workflowId, String traceId, String debugMode,
            DebugEvidenceMode evidenceMode, String providerMode, String modelProvider, String promptVersion,
            String question, RetrievalDebug retrieval, AnswerabilityDebug answerability, SafetyDebug safety,
            SupplierGatewayDebug supplierGateway, List<ToolCallDebug> toolCalls, DebugFinalRoute finalRoute,
            HumanHandoffRecommendation humanHandoffRecommendation, List<String> diagnosticWarnings) {
        this(sessionId, workflowId, traceId, debugMode, evidenceMode, providerMode, modelProvider, promptVersion,
                question, retrieval, answerability, safety, supplierGateway, toolCalls, finalRoute,
                humanHandoffRecommendation, diagnosticWarnings, "COMPLETE", List.of(), List.of(),
                new DiagnosticDebug("AVAILABLE", evidenceMode == DebugEvidenceMode.TRACE_REPLAY
                        ? "TRACE_EVIDENCE" : "IN_MEMORY_OBSERVATION"));
    }

    public record DiagnosticDebug(String status, String source) { }
    public record RetrievalDebug(
            List<CitationDebug> candidates,
            List<CitationDebug> acceptedCitations,
            List<CitationDebug> rejectedCitations
    ) {
    }

    public record CitationDebug(
            String retrievalRunId,
            Long documentId,
            Long docId,
            Long chunkId,
            String title,
            String sourceUri,
            String sourceAnchor,
            String policyVersion,
            String effectiveFrom,
            String effectiveTo,
            String effectiveTime,
            Double score,
            String rejectionReason
    ) {
    }

    public record AnswerabilityDebug(String decision, String reason) {
    }

    public record SafetyDebug(String decision, String reason, List<String> riskTags) {
    }

    public record SupplierGatewayDebug(boolean participated, String skippedReason) {
    }

    public record ToolCallDebug(String name, String status, String errorCode, String outputRef) {
    }

    public record HumanHandoffRecommendation(boolean recommended, String reason) {
    }
}
