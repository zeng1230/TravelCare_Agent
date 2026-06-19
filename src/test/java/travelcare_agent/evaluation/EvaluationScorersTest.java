package travelcare_agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import travelcare_agent.evaluation.scoring.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationScorersTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void policyDecisionPassesAndFailsDeterministically() throws Exception {
        PolicyDecisionScorer scorer = new PolicyDecisionScorer();
        EvaluationScoringContext pass = context(Map.of("expectedPolicyDecision", "ELIGIBLE"), "ELIGIBLE");
        EvaluationScoringContext fail = context(Map.of("expectedPolicyDecision", "INELIGIBLE"), "ELIGIBLE");
        assertThat(scorer.score(pass).passed()).isTrue();
        assertThat(scorer.score(fail).passed()).isFalse();
    }

    @Test
    void missingExpectationIsNotApplied() throws Exception {
        ScoreResult result = new PolicyDecisionScorer().score(context(Map.of(), "ELIGIBLE"));
        assertThat(result.applied()).isFalse();
        assertThat(result.score()).isEqualTo(1);
    }

    private EvaluationScoringContext context(Map<String,Object> expectation,String decision) throws Exception {
        return EvaluationScoringContext.builder()
                .expectation(mapper.valueToTree(expectation))
                .policyDecision(decision).workflowStatus("RESPONDED")
                .spanTypes(List.of("WORKFLOW","POLICY","MODEL"))
                .eventNames(List.of("POLICY_DECISION"))
                .output("可以申请退款").riskLevel("LOW")
                .sideEffectCheckResult(new SideEffectCheckResult(true, Map.of(), Map.of(), null))
                .build();
    }
}
