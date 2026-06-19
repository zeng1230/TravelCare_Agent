package travelcare_agent.answerability;

import org.springframework.stereotype.Service;
import travelcare_agent.retrieval.service.RetrievalSnippet;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AnswerabilityService {
    public static final double DEFAULT_MIN_SCORE = 0.5;
    public static final String DEFAULT_FALLBACK_MESSAGE =
            "I don't have enough verified knowledge to answer that from the knowledge base. Manual support can help verify it.";

    public AnswerabilityDecision assess(AnswerabilityRequest request) {
        LocalDateTime now = request != null && request.assessedAt() != null
                ? request.assessedAt() : LocalDateTime.now();
        BusinessDecisionContext business = request == null || request.businessDecisionContext() == null
                ? BusinessDecisionContext.none() : request.businessDecisionContext();
        List<RetrievalSnippet> snippets = request == null || request.retrievalSnippets() == null
                ? List.of() : request.retrievalSnippets();

        if (business.businessDecisionLocked()) {
            return new AnswerabilityDecision(
                    AnswerabilityStatus.NOT_APPLICABLE,
                    AnswerabilityReasonCode.BUSINESS_DECISION_ONLY,
                    AnswerabilityRequiredAction.ALLOW_MODEL,
                    CitationPolicy.FORBIDDEN,
                    List.of(),
                    true,
                    hasUsableCitation(snippets, now),
                    false,
                    List.of(),
                    rejected(snippets, now),
                    null
            );
        }

        if (snippets.isEmpty()) {
            return fallback(AnswerabilityReasonCode.NO_RETRIEVAL, List.of());
        }

        CitationBuildResult citationResult = buildCitations(snippets, now);
        if (!citationResult.citations().isEmpty()) {
            AnswerabilityStatus status = citationResult.citations().size() < snippets.size()
                    ? AnswerabilityStatus.PARTIAL : AnswerabilityStatus.ANSWERABLE;
            return new AnswerabilityDecision(
                    status,
                    AnswerabilityReasonCode.SUFFICIENT_CONTEXT,
                    AnswerabilityRequiredAction.ALLOW_MODEL,
                    CitationPolicy.REQUIRED,
                    citationResult.citations().stream().map(CitationMetadata::chunkId).toList(),
                    false,
                    false,
                    false,
                    citationResult.citations(),
                    citationResult.rejected(),
                    null
            );
        }

        AnswerabilityReasonCode reason = citationResult.rejected().stream()
                .anyMatch(candidate -> candidate.reasonCode() == RejectedCitationReasonCode.EXPIRED_SOURCE)
                ? AnswerabilityReasonCode.EXPIRED_SOURCE : AnswerabilityReasonCode.LOW_MATCH;
        return fallback(reason, citationResult.rejected());
    }

    private static AnswerabilityDecision fallback(AnswerabilityReasonCode reason,
            List<RejectedCitationCandidate> rejected) {
        return new AnswerabilityDecision(
                AnswerabilityStatus.UNANSWERABLE,
                reason,
                AnswerabilityRequiredAction.FALLBACK_REPLY,
                CitationPolicy.OPTIONAL,
                List.of(),
                false,
                false,
                false,
                List.of(),
                rejected == null ? List.of() : rejected,
                DEFAULT_FALLBACK_MESSAGE
        );
    }

    private static boolean hasUsableCitation(List<RetrievalSnippet> snippets, LocalDateTime now) {
        return snippets.stream().anyMatch(snippet -> rejectionReason(snippet, now) == null);
    }

    private static List<RejectedCitationCandidate> rejected(List<RetrievalSnippet> snippets, LocalDateTime now) {
        return buildCitations(snippets, now).rejected();
    }

    private static CitationBuildResult buildCitations(List<RetrievalSnippet> snippets, LocalDateTime now) {
        String currentRunId = snippets.stream()
                .map(RetrievalSnippet::retrievalRunId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse(null);

        List<CitationMetadata> citations = new ArrayList<>();
        List<RejectedCitationCandidate> rejected = new ArrayList<>();
        for (RetrievalSnippet snippet : snippets) {
            RejectedCitationReasonCode reason = rejectionReason(snippet, now);
            if (reason == null && currentRunId != null && !currentRunId.equals(snippet.retrievalRunId())) {
                reason = RejectedCitationReasonCode.NOT_FROM_CURRENT_RETRIEVAL;
            }
            if (reason == null) {
                citations.add(new CitationMetadata(
                        snippet.retrievalRunId(),
                        snippet.chunkId(),
                        snippet.documentId(),
                        snippet.title(),
                        snippet.sourceUri(),
                        snippet.effectiveFrom(),
                        snippet.effectiveTo()
                ));
            } else {
                rejected.add(new RejectedCitationCandidate(
                        snippet.retrievalRunId(),
                        snippet.chunkId(),
                        snippet.documentId(),
                        snippet.title(),
                        snippet.sourceUri(),
                        snippet.effectiveFrom(),
                        snippet.effectiveTo(),
                        reason
                ));
            }
        }
        return new CitationBuildResult(citations, rejected);
    }

    private static RejectedCitationReasonCode rejectionReason(RetrievalSnippet snippet, LocalDateTime now) {
        if (snippet.retrievalRunId() == null || snippet.retrievalRunId().isBlank()) {
            return RejectedCitationReasonCode.MISSING_RETRIEVAL_RUN;
        }
        if (snippet.effectiveFrom() != null && snippet.effectiveFrom().isAfter(now)) {
            return RejectedCitationReasonCode.EXPIRED_SOURCE;
        }
        if (snippet.effectiveTo() != null && snippet.effectiveTo().isBefore(now)) {
            return RejectedCitationReasonCode.EXPIRED_SOURCE;
        }
        if (snippet.score() < DEFAULT_MIN_SCORE) {
            return RejectedCitationReasonCode.LOW_MATCH;
        }
        return null;
    }

    private record CitationBuildResult(
            List<CitationMetadata> citations,
            List<RejectedCitationCandidate> rejected
    ) {
    }
}
