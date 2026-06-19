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
                {"expectedAnswerabilityStatus":"ANSWERABLE","requireCitation":true,
                 "expectedCitationChunkIds":[101],"forbidExpiredCitation":true,
                 "forbidRagBusinessOverride":true,"expectedRequiredAction":"FALLBACK_REPLY"}
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
                 "requireCitation":true,"expectedCitationChunkIds":[101],"forbidExpiredCitation":true}
                """, true);

        assertThat(new AnswerabilityDecisionScorer().score(c).passed()).isTrue();
        assertThat(new CitationRequiredScorer().score(c).passed()).isTrue();
        assertThat(new CitationSourceScorer().score(c).passed()).isTrue();
        assertThat(new ExpiredCitationScorer().score(c).passed()).isTrue();
    }

    @Test
    void citationScorersFailForMissingOrExpiredFinalCitation() throws Exception {
        EvaluationScoringContext c = context("""
                {"expectedCitationChunkIds":[999],"forbidExpiredCitation":true}
                """, true);
        c.citations = json.readTree("""
                [{"chunkId":101,"effectiveTo":"2026-01-01T00:00:00"}]
                """);

        assertThat(new CitationSourceScorer().score(c).passed()).isFalse();
        assertThat(new ExpiredCitationScorer().score(c).passed()).isFalse();
    }

    @Test
    void businessOverrideGuardFailsForOverrideOrUnlockedBusinessWorkflow() throws Exception {
        EvaluationScoringContext override = context("{\"forbidRagBusinessOverride\":true}", true);
        override.ragMayOverrideBusinessDecision = true;
        assertThat(new BusinessOverrideGuardScorer().score(override).passed()).isFalse();

        EvaluationScoringContext unlocked = context("{\"forbidRagBusinessOverride\":true}", true);
        unlocked.ragMayOverrideBusinessDecision = false;
        unlocked.businessDecisionLocked = false;
        assertThat(new BusinessOverrideGuardScorer().score(unlocked).passed()).isFalse();
    }

    @Test
    void fallbackScorerRequiresRequiredActionAndFallbackUsedMetadata() throws Exception {
        EvaluationScoringContext pass = context("{\"expectedRequiredAction\":\"FALLBACK_REPLY\"}", true);
        pass.requiredAction = "FALLBACK_REPLY";
        pass.fallbackUsed = true;
        assertThat(new RagFallbackScorer().score(pass).passed()).isTrue();

        EvaluationScoringContext fail = context("{\"expectedRequiredAction\":\"FALLBACK_REPLY\"}", true);
        fail.requiredAction = "FALLBACK_REPLY";
        fail.fallbackUsed = false;
        assertThat(new RagFallbackScorer().score(fail).passed()).isFalse();
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
                     "businessDecisionLocked":true,"ragMayExplainBusinessDecision":true,
                     "ragMayOverrideBusinessDecision":false}
                    """);
            c.citationSummarySnapshot = json.readTree("""
                    {"citations":[{"chunkId":101,"effectiveTo":"2026-12-31T00:00:00"}],
                     "rejectedCitationCandidates":[{"chunkId":102,"reasonCode":"EXPIRED_SOURCE"}]}
                    """);
            c.answerabilityStatus = "ANSWERABLE";
            c.answerabilityReasonCode = "SUFFICIENT_CONTEXT";
            c.requiredAction = "ALLOW_MODEL";
            c.businessDecisionLocked = true;
            c.ragMayExplainBusinessDecision = true;
            c.ragMayOverrideBusinessDecision = false;
            c.citations = c.citationSummarySnapshot.path("citations");
            c.rejectedCitationCandidates = c.citationSummarySnapshot.path("rejectedCitationCandidates");
        }
        return c;
    }
}
