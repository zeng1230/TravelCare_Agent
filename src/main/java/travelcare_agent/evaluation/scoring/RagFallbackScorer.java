package travelcare_agent.evaluation.scoring;

import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class RagFallbackScorer implements EvaluationScorer {
    public String name(){return "ragFallback";}
    public ScoreResult score(EvaluationScoringContext c){
        Stage9EvaluationExpectation expectation=Stage9ScoringSupport.expectation(c);
        if(!expectation.hasFallbackExpectation())return ScoreResult.skipped(name());
        if(!Stage9ScoringSupport.hasStage9Snapshots(c))return ScoreResult.skipped(name());
        String expectedAction=expectation.expectedRequiredAction();
        Boolean expectedUsed=expectation.expectedFallbackUsed();
        boolean actionMatched=expectedAction==null||expectedAction.equals(c.requiredAction());
        boolean usageMatched=expectedUsed==null||expectedUsed.equals(c.fallbackUsed());
        boolean passed=actionMatched&&usageMatched;
        Map<String,Object> expected=new java.util.LinkedHashMap<>();expected.put("requiredAction",expectedAction);expected.put("fallbackUsed",expectedUsed);
        return ScoreResult.of(name(),passed,expected,Stage9ScoringSupport.actual(c),
                passed?"RAG fallback expectation matched":"RAG fallback expectation mismatch");
    }
}
