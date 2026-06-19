package travelcare_agent.answerability;

import java.util.List;

public record AnswerabilityDecision(
        AnswerabilityStatus status,
        AnswerabilityReasonCode reasonCode,
        AnswerabilityRequiredAction requiredAction,
        CitationPolicy citationPolicy,
        List<Long> evidenceChunkIds,
        boolean businessDecisionLocked,
        boolean ragMayExplainBusinessDecision,
        boolean ragMayOverrideBusinessDecision,
        List<CitationMetadata> citations,
        List<RejectedCitationCandidate> rejectedCitationCandidates,
        String fallbackMessage
) {
}
