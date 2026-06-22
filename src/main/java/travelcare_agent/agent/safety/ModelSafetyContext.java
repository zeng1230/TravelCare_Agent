package travelcare_agent.agent.safety;

import travelcare_agent.answerability.CitationMetadata;
import travelcare_agent.answerability.CitationPolicy;
import travelcare_agent.retrieval.service.RetrievalSnippet;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public record ModelSafetyContext(
        String operation,
        Set<String> allowedIntents,
        boolean orderReferenceRequired,
        boolean knowledgeOperation,
        CitationPolicy citationPolicy,
        List<CitationMetadata> allowedCitations,
        List<RetrievalSnippet> retrievalSnippets,
        boolean businessDecisionLocked,
        String authoritativeAnswer,
        String authoritativeDecision,
        LocalDateTime assessedAt
) {
    public ModelSafetyContext {
        allowedIntents = allowedIntents == null ? Set.of() : Set.copyOf(allowedIntents);
        citationPolicy = citationPolicy == null ? CitationPolicy.OPTIONAL : citationPolicy;
        allowedCitations = allowedCitations == null ? List.of() : List.copyOf(allowedCitations);
        retrievalSnippets = retrievalSnippets == null ? List.of() : List.copyOf(retrievalSnippets);
        assessedAt = assessedAt == null ? LocalDateTime.now() : assessedAt;
    }

    public static ModelSafetyContext intentClassification() {
        return new ModelSafetyContext("INTENT_CLASSIFICATION",
                Set.of("REFUND_INQUIRY", "ORDER_QUERY", "FAQ", "SOP", "KNOWLEDGE_QUERY"),
                false, false, CitationPolicy.OPTIONAL, List.of(), List.of(), false,
                null, null, LocalDateTime.now());
    }
}
