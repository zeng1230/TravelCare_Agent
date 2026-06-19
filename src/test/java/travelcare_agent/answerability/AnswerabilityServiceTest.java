package travelcare_agent.answerability;

import org.junit.jupiter.api.Test;
import travelcare_agent.retrieval.service.RetrievalSnippet;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerabilityServiceTest {

    private final AnswerabilityService service = new AnswerabilityService();

    @Test
    void marksValidSopRetrievalAsAnswerableWithCitationFromSameRetrievalRun() {
        LocalDateTime now = LocalDateTime.parse("2026-06-16T10:00:00");
        RetrievalSnippet snippet = snippet("run-1", 2001L, 1001L, "Refund SOP",
                now.minusDays(1), now.plusDays(1), 0.91);

        AnswerabilityDecision decision = service.assess(new AnswerabilityRequest(
                "Can you explain the refund SOP?",
                List.of(snippet),
                "FAQ",
                "order_refund_inquiry",
                BusinessDecisionContext.none(),
                now
        ));

        assertThat(decision.status()).isEqualTo(AnswerabilityStatus.ANSWERABLE);
        assertThat(decision.reasonCode()).isEqualTo(AnswerabilityReasonCode.SUFFICIENT_CONTEXT);
        assertThat(decision.requiredAction()).isEqualTo(AnswerabilityRequiredAction.ALLOW_MODEL);
        assertThat(decision.citationPolicy()).isEqualTo(CitationPolicy.REQUIRED);
        assertThat(decision.evidenceChunkIds()).containsExactly(1001L);
        assertThat(decision.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.retrievalRunId()).isEqualTo("run-1");
            assertThat(citation.chunkId()).isEqualTo(1001L);
            assertThat(citation.documentId()).isEqualTo(2001L);
            assertThat(citation.title()).isEqualTo("Refund SOP");
        });
        assertThat(decision.rejectedCitationCandidates()).isEmpty();
    }

    @Test
    void fallsBackDeterministicallyWhenRetrievalIsEmpty() {
        AnswerabilityDecision decision = service.assess(new AnswerabilityRequest(
                "What is the policy?",
                List.of(),
                "FAQ",
                "order_refund_inquiry",
                BusinessDecisionContext.none(),
                LocalDateTime.parse("2026-06-16T10:00:00")
        ));

        assertThat(decision.status()).isEqualTo(AnswerabilityStatus.UNANSWERABLE);
        assertThat(decision.reasonCode()).isEqualTo(AnswerabilityReasonCode.NO_RETRIEVAL);
        assertThat(decision.requiredAction()).isEqualTo(AnswerabilityRequiredAction.FALLBACK_REPLY);
        assertThat(decision.citations()).isEmpty();
        assertThat(decision.fallbackMessage()).contains("I don't have enough verified knowledge");
    }

    @Test
    void rejectsExpiredCitationCandidatesAndExcludesThemFromFinalCitations() {
        LocalDateTime now = LocalDateTime.parse("2026-06-16T10:00:00");
        RetrievalSnippet expired = snippet("run-expired", 2001L, 1001L, "Expired SOP",
                now.minusDays(10), now.minusDays(1), 0.93);

        AnswerabilityDecision decision = service.assess(new AnswerabilityRequest(
                "Can you explain the old refund SOP?",
                List.of(expired),
                "FAQ",
                "order_refund_inquiry",
                BusinessDecisionContext.none(),
                now
        ));

        assertThat(decision.status()).isEqualTo(AnswerabilityStatus.UNANSWERABLE);
        assertThat(decision.reasonCode()).isEqualTo(AnswerabilityReasonCode.EXPIRED_SOURCE);
        assertThat(decision.citations()).isEmpty();
        assertThat(decision.rejectedCitationCandidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.retrievalRunId()).isEqualTo("run-expired");
            assertThat(candidate.chunkId()).isEqualTo(1001L);
            assertThat(candidate.reasonCode()).isEqualTo(RejectedCitationReasonCode.EXPIRED_SOURCE);
        });
    }

    @Test
    void rejectsCitationCandidatesFromDifferentRetrievalRuns() {
        LocalDateTime now = LocalDateTime.parse("2026-06-16T10:00:00");
        RetrievalSnippet current = snippet("run-current", 2001L, 1001L, "Current SOP",
                now.minusDays(1), now.plusDays(1), 0.9);
        RetrievalSnippet staleRun = snippet("run-other", 2002L, 1002L, "Other Run SOP",
                now.minusDays(1), now.plusDays(1), 0.9);

        AnswerabilityDecision decision = service.assess(new AnswerabilityRequest(
                "Can you explain the refund SOP?",
                List.of(current, staleRun),
                "FAQ",
                "order_refund_inquiry",
                BusinessDecisionContext.none(),
                now
        ));

        assertThat(decision.citations()).singleElement()
                .extracting(CitationMetadata::chunkId)
                .isEqualTo(1001L);
        assertThat(decision.rejectedCitationCandidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.chunkId()).isEqualTo(1002L);
            assertThat(candidate.reasonCode()).isEqualTo(RejectedCitationReasonCode.NOT_FROM_CURRENT_RETRIEVAL);
        });
    }

    @Test
    void marksSomeValidAndSomeRejectedEvidenceAsPartial() {
        LocalDateTime now = LocalDateTime.parse("2026-06-16T10:00:00");
        RetrievalSnippet valid = snippet("run-partial", 2001L, 1001L, "Refund SOP",
                now.minusDays(1), now.plusDays(1), 0.9);
        RetrievalSnippet weak = snippet("run-partial", 2002L, 1002L, "Weak SOP",
                now.minusDays(1), now.plusDays(1), 0.2);

        AnswerabilityDecision decision = service.assess(new AnswerabilityRequest(
                "Can you explain refund background?",
                List.of(valid, weak),
                "FAQ",
                "order_refund_inquiry",
                BusinessDecisionContext.none(),
                now
        ));

        assertThat(decision.status()).isEqualTo(AnswerabilityStatus.PARTIAL);
        assertThat(decision.citations()).singleElement().extracting(CitationMetadata::chunkId).isEqualTo(1001L);
        assertThat(decision.rejectedCitationCandidates()).singleElement()
                .extracting(RejectedCitationCandidate::reasonCode)
                .isEqualTo(RejectedCitationReasonCode.LOW_MATCH);
    }

    @Test
    void locksBusinessDecisionAndForbidsRagOverride() {
        LocalDateTime now = LocalDateTime.parse("2026-06-16T10:00:00");
        RetrievalSnippet snippet = snippet("run-business", 2001L, 1001L, "Refund SOP",
                now.minusDays(1), now.plusDays(1), 0.9);

        AnswerabilityDecision decision = service.assess(new AnswerabilityRequest(
                "Can I get a refund amount different from the order result?",
                List.of(snippet),
                "REFUND_INQUIRY",
                "order_refund_inquiry",
                new BusinessDecisionContext(true, "RESPONDED", "ELIGIBLE", "PAID", "399.00"),
                now
        ));

        assertThat(decision.status()).isEqualTo(AnswerabilityStatus.NOT_APPLICABLE);
        assertThat(decision.reasonCode()).isEqualTo(AnswerabilityReasonCode.BUSINESS_DECISION_ONLY);
        assertThat(decision.citationPolicy()).isEqualTo(CitationPolicy.FORBIDDEN);
        assertThat(decision.businessDecisionLocked()).isTrue();
        assertThat(decision.ragMayExplainBusinessDecision()).isTrue();
        assertThat(decision.ragMayOverrideBusinessDecision()).isFalse();
    }

    private static RetrievalSnippet snippet(String retrievalRunId, Long documentId, Long chunkId, String title,
            LocalDateTime effectiveFrom, LocalDateTime effectiveTo, double score) {
        return new RetrievalSnippet(
                retrievalRunId,
                documentId,
                chunkId,
                title,
                "Refund policy content",
                "https://example.com/refund",
                effectiveFrom,
                effectiveTo,
                score
        );
    }
}
