package travelcare_agent.eval;

import java.util.List;

public record EvaluationCaseResult(
        String caseId,
        String description,
        String inputMessage,
        String expectedWorkflowStatus,
        String actualWorkflowStatus,
        String expectedRefundDecision,
        String actualRefundDecision,
        boolean expectedRetrievalHit,
        List<Long> actualRetrievalChunkIds,
        boolean expectedMemoryUsage,
        List<Long> actualMemoryIds,
        List<String> expectedAuditActions,
        List<String> actualAuditActions,
        boolean expectedNoUnsafeOverride,
        boolean actualUnsafeOverride,
        Long agentRunId,
        String agentRunStatus,
        String replayEndpoint,
        boolean passed,
        String failureReason
) {
}
