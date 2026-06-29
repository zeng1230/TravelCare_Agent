package travelcare_agent.evaluation.scoring;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AnswerabilityDecisionScorer implements EvaluationScorer {
    public String name() {
        return "answerabilityDecision";
    }

    public ScoreResult score(EvaluationScoringContext c) {
        Stage9EvaluationExpectation expectation = Stage9ScoringSupport.expectation(c);
        if (!expectation.hasAnswerabilityExpectation()) return ScoreResult.skipped(name());
        if (!Stage9ScoringSupport.hasStage9Snapshots(c)) return ScoreResult.skipped(name());
        String status = expectation.expectedAnswerabilityStatus();
        String reason = expectation.expectedAnswerabilityReasonCode();
        String action = expectation.expectedRequiredAction();
        boolean matched = (status == null || status.equals(c.answerabilityStatus()))
                && (reason == null || reason.equals(c.answerabilityReasonCode()))
                && (action == null || action.equals(c.requiredAction()));
        Map<String, Object> expected = new java.util.LinkedHashMap<>();
        expected.put("status", status);
        expected.put("reasonCode", reason);
        expected.put("requiredAction", action);
        return ScoreResult.of(name(), matched, expected,
                Stage9ScoringSupport.actual(c), matched ? "answerability decision matched" : "answerability decision mismatch");
    }
}
