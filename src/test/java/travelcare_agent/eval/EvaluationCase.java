package travelcare_agent.eval;

import java.util.List;

public record EvaluationCase(
        String caseId,
        String description,
        Long userId,
        String inputMessage,
        String idempotencyKey,
        String expectedWorkflowStatus,
        String expectedRefundDecision,
        boolean expectedRetrievalHit,
        boolean expectedMemoryUsage,
        List<String> expectedAuditActions,
        boolean expectedNoUnsafeOverride
) {
}
