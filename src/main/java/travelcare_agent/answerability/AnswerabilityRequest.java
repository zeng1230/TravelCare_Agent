package travelcare_agent.answerability;

import travelcare_agent.retrieval.service.RetrievalSnippet;

import java.time.LocalDateTime;
import java.util.List;

public record AnswerabilityRequest(
        String userMessage,
        List<RetrievalSnippet> retrievalSnippets,
        String intent,
        String workflowType,
        BusinessDecisionContext businessDecisionContext,
        LocalDateTime assessedAt
) {
}
