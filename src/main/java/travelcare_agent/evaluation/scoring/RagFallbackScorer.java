package travelcare_agent.evaluation.scoring;

import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class RagFallbackScorer implements EvaluationScorer {
    public String name(){return "ragFallback";}
    public ScoreResult score(EvaluationScoringContext c){
        var expected=Stage9ScoringSupport.expectation(c,"expectedRequiredAction");
        if(expected==null||!"FALLBACK_REPLY".equals(expected.asText()))return ScoreResult.skipped(name());
        if(!Stage9ScoringSupport.hasStage9Snapshots(c))return ScoreResult.skipped(name());
        boolean actionMatched="FALLBACK_REPLY".equals(c.requiredAction());
        boolean fallbackUsed=Boolean.TRUE.equals(c.fallbackUsed());
        boolean passed=actionMatched&&fallbackUsed;
        return ScoreResult.of(name(),passed,Map.of("requiredAction","FALLBACK_REPLY","fallbackUsed",true),
                Stage9ScoringSupport.actual(c),passed?"deterministic RAG fallback used":"required fallback was not proven by metadata");
    }
}
