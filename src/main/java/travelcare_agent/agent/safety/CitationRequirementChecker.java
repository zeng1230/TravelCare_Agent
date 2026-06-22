package travelcare_agent.agent.safety;

import travelcare_agent.answerability.CitationMetadata;
import travelcare_agent.answerability.CitationPolicy;
import travelcare_agent.retrieval.service.RetrievalSnippet;

import java.time.LocalDateTime;

public class CitationRequirementChecker {
    public String rejectionReason(StructuredModelOutput output, ModelSafetyContext context) {
        if (context.citationPolicy() == CitationPolicy.FORBIDDEN && !output.citations().isEmpty()) {
            return "RAG_BUSINESS_OVERRIDE";
        }
        if (context.citationPolicy() == CitationPolicy.REQUIRED && output.citations().isEmpty()) {
            return "CITATION_REQUIRED";
        }
        for (CitationRef ref : output.citations()) {
            if (!allowed(ref, context)) return "CITATION_OUTSIDE_CONTEXT";
        }
        return null;
    }

    private boolean allowed(CitationRef ref, ModelSafetyContext context) {
        boolean citationAllowed = context.allowedCitations().stream().anyMatch(citation -> matches(ref, citation));
        if (!citationAllowed) return false;
        LocalDateTime now = context.assessedAt();
        return context.retrievalSnippets().stream().anyMatch(snippet -> matches(ref, snippet)
                && snippet.score() >= 0.5
                && (snippet.effectiveFrom() == null || !snippet.effectiveFrom().isAfter(now))
                && (snippet.effectiveTo() == null || !snippet.effectiveTo().isBefore(now)));
    }

    private static boolean matches(CitationRef ref, CitationMetadata citation) {
        return java.util.Objects.equals(ref.retrievalRunId(), citation.retrievalRunId())
                && java.util.Objects.equals(ref.documentId(), citation.documentId())
                && java.util.Objects.equals(ref.chunkId(), citation.chunkId());
    }

    private static boolean matches(CitationRef ref, RetrievalSnippet snippet) {
        return java.util.Objects.equals(ref.retrievalRunId(), snippet.retrievalRunId())
                && java.util.Objects.equals(ref.documentId(), snippet.documentId())
                && java.util.Objects.equals(ref.chunkId(), snippet.chunkId());
    }
}
