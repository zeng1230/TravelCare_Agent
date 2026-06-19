package travelcare_agent.evaluation.scoring;

import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class AnswerabilityDecisionScorer implements EvaluationScorer {
    public String name(){return "answerabilityDecision";}
    public ScoreResult score(EvaluationScoringContext c){
        var expectedStatus=Stage9ScoringSupport.expectation(c,"expectedAnswerabilityStatus");
        if(expectedStatus==null||expectedStatus.isNull())return ScoreResult.skipped(name());
        if(!Stage9ScoringSupport.hasStage9Snapshots(c))return ScoreResult.skipped(name());
        String status=expectedStatus.asText();
        var expectedReason=Stage9ScoringSupport.expectation(c,"expectedAnswerabilityReasonCode");
        String reason=Stage9ScoringSupport.text(expectedReason);
        boolean matched=status.equals(c.answerabilityStatus())&&(reason==null||reason.equals(c.answerabilityReasonCode()));
        return ScoreResult.of(name(),matched,Map.of("status",status,"reasonCode",reason==null?"":reason),
                Stage9ScoringSupport.actual(c),matched?"answerability decision matched":"answerability decision mismatch");
    }
}
