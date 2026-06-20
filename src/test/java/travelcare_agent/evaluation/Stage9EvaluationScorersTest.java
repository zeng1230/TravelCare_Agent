package travelcare_agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import travelcare_agent.evaluation.entity.EvaluationCase;
import travelcare_agent.evaluation.scoring.*;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class Stage9EvaluationScorersTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void stage9ScorersReturnNotAppliedWhenSnapshotsAreMissing() throws Exception {
        EvaluationScoringContext c = context("""
                {"expectedAnswerabilityStatus":"ANSWERABLE","expectCitationRequired":true,
                 "expectedCitationChunkIds":[101],"expectedCitationDocumentIds":[201],
                 "expectNoExpiredCitation":true,"expectBusinessDecisionLocked":true,
                 "expectRagMayOverrideBusinessDecision":false,"expectedFallbackUsed":false}
                """, false);

        assertThat(new AnswerabilityDecisionScorer().score(c).applied()).isFalse();
        assertThat(new CitationRequiredScorer().score(c).applied()).isFalse();
        assertThat(new CitationSourceScorer().score(c).applied()).isFalse();
        assertThat(new ExpiredCitationScorer().score(c).applied()).isFalse();
        assertThat(new BusinessOverrideGuardScorer().score(c).applied()).isFalse();
        assertThat(new RagFallbackScorer().score(c).applied()).isFalse();
    }

    @Test
    void answerabilityAndCitationScorersPassForValidStage9Metadata() throws Exception {
        EvaluationScoringContext c = context("""
                {"expectedAnswerabilityStatus":"ANSWERABLE","expectedAnswerabilityReasonCode":"SUFFICIENT_CONTEXT",
                 "expectedRequiredAction":"ALLOW_MODEL","expectCitationRequired":true,
                 "expectedCitationChunkIds":[101],"expectedCitationDocumentIds":[201],
                 "expectNoExpiredCitation":true,"expectedFallbackUsed":false,
                 "expectBusinessDecisionLocked":true,"expectRagMayOverrideBusinessDecision":false}
                """, true);

        assertAppliedAndPassed(new AnswerabilityDecisionScorer().score(c));
        assertAppliedAndPassed(new CitationRequiredScorer().score(c));
        assertAppliedAndPassed(new CitationSourceScorer().score(c));
        assertAppliedAndPassed(new ExpiredCitationScorer().score(c));
        assertAppliedAndPassed(new RagFallbackScorer().score(c));
        assertAppliedAndPassed(new BusinessOverrideGuardScorer().score(c));
    }

    @Test
    void citationScorersFailForMissingOrExpiredFinalCitation() throws Exception {
        EvaluationScoringContext c = context("""
                {"expectedCitationChunkIds":[999],"expectedCitationDocumentIds":[999],
                 "expectNoExpiredCitation":true}
                """, true);
        c.citations = json.readTree("""
                [{"chunkId":101,"documentId":201,"effectiveTo":"2026-01-01T00:00:00"}]
                """);

        assertThat(new CitationSourceScorer().score(c).passed()).isFalse();
        assertThat(new ExpiredCitationScorer().score(c).passed()).isFalse();
    }

    @Test
    void businessOverrideGuardFailsForOverrideOrUnlockedBusinessWorkflow() throws Exception {
        EvaluationScoringContext override = context("""
                {"expectBusinessDecisionLocked":true,"expectRagMayOverrideBusinessDecision":false}
                """, true);
        override.ragMayOverrideBusinessDecision = true;
        assertThat(new BusinessOverrideGuardScorer().score(override)).satisfies(result -> {
            assertThat(result.applied()).isTrue();
            assertThat(result.passed()).isFalse();
        });

        EvaluationScoringContext unlocked = context("""
                {"expectBusinessDecisionLocked":true,"expectRagMayOverrideBusinessDecision":false}
                """, true);
        unlocked.ragMayOverrideBusinessDecision = false;
        unlocked.businessDecisionLocked = false;
        assertThat(new BusinessOverrideGuardScorer().score(unlocked)).satisfies(result -> {
            assertThat(result.applied()).isTrue();
            assertThat(result.passed()).isFalse();
        });
    }

    @Test
    void fallbackScorerRequiresRequiredActionAndFallbackUsedMetadata() throws Exception {
        EvaluationScoringContext pass = context("""
                {"expectedRequiredAction":"FALLBACK_REPLY","expectedFallbackUsed":true,
                 "expectCitationRequired":false}
                """, true);
        pass.answerabilityStatus = "UNANSWERABLE";
        pass.requiredAction = "FALLBACK_REPLY";
        pass.fallbackUsed = true;
        pass.citations = json.createArrayNode();
        assertAppliedAndPassed(new RagFallbackScorer().score(pass));
        assertAppliedAndPassed(new CitationRequiredScorer().score(pass));

        EvaluationScoringContext fail = context("""
                {"expectedRequiredAction":"FALLBACK_REPLY","expectedFallbackUsed":true}
                """, true);
        fail.requiredAction = "FALLBACK_REPLY";
        fail.fallbackUsed = false;
        assertThat(new RagFallbackScorer().score(fail)).satisfies(result -> {
            assertThat(result.applied()).isTrue();
            assertThat(result.passed()).isFalse();
        });
    }

    @Test
    void expiredCitationScorerAppliesWhenExpiredCitationWasFiltered() throws Exception {
        EvaluationScoringContext c = context("{\"expectNoExpiredCitation\":true}", true);
        c.answerabilityStatus = "UNANSWERABLE";
        c.citations = json.createArrayNode();
        c.rejectedCitationCandidates = json.readTree("""
                [{"chunkId":102,"documentId":202,"reasonCode":"EXPIRED_SOURCE"}]
                """);

        assertAppliedAndPassed(new ExpiredCitationScorer().score(c));
    }

    private void assertAppliedAndPassed(ScoreResult result) {
        assertThat(result.applied()).isTrue();
        assertThat(result.passed()).isTrue();
    }

    private EvaluationScoringContext context(String expectation, boolean withSnapshots) throws Exception {
        EvaluationCase evaluationCase = new EvaluationCase();
        evaluationCase.setCaseKey("refund_case");
        evaluationCase.setTagsJson("[\"refund\"]");
        EvaluationScoringContext c = EvaluationScoringContext.builder()
                .expectation(json.readTree(expectation))
                .workflowStatus("RESPONDED")
                .riskLevel("LOW")
                .sideEffectCheckResult(new SideEffectCheckResult(true, Map.of(), Map.of(), null))
                .build();
        c.evaluationCase = evaluationCase;
        c.clock = Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);
        if (withSnapshots) {
            c.answerabilityDecisionSnapshot = json.readTree("""
                    {"status":"ANSWERABLE","reasonCode":"SUFFICIENT_CONTEXT","requiredAction":"ALLOW_MODEL",
                     "evidenceChunkIds":[101],
                     "businessDecisionLocked":true,"ragMayExplainBusinessDecision":true,
                     "ragMayOverrideBusinessDecision":false}
                    """);
            c.citationSummarySnapshot = json.readTree("""
                    {"citations":[{"chunkId":101,"documentId":201,"retrievalRunId":"run-1",
                                    "effectiveTo":"2026-12-31T00:00:00"}],
                     "rejectedCitationCandidates":[{"chunkId":102,"documentId":202,
                                                     "reasonCode":"EXPIRED_SOURCE"}]}
                    """);
            c.answerabilityStatus = "ANSWERABLE";
            c.answerabilityReasonCode = "SUFFICIENT_CONTEXT";
            c.requiredAction = "ALLOW_MODEL";
            c.businessDecisionLocked = true;
            c.ragMayExplainBusinessDecision = true;
            c.ragMayOverrideBusinessDecision = false;
            c.fallbackUsed = false;
            c.citations = c.citationSummarySnapshot.path("citations");
            c.rejectedCitationCandidates = c.citationSummarySnapshot.path("rejectedCitationCandidates");
        }
        return c;
    }
}
