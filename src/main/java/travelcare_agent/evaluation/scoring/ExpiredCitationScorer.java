package travelcare_agent.evaluation.scoring;

import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
public class ExpiredCitationScorer implements EvaluationScorer {
    public String name(){return "expiredCitation";}
    public ScoreResult score(EvaluationScoringContext c){
        Boolean expected=Stage9ScoringSupport.expectation(c).expectNoExpiredCitation();
        if(expected==null)return ScoreResult.skipped(name());
        if(!Stage9ScoringSupport.hasStage9Snapshots(c))return ScoreResult.skipped(name());
        LocalDateTime now=c.clock==null?LocalDateTime.now():LocalDateTime.now(c.clock);
        boolean expired=Stage9ScoringSupport.containsExpiredCitation(c.citations(),now);
        boolean noExpired=!expired;
        boolean matched=expected==noExpired;
        return ScoreResult.of(name(),matched,expected,Stage9ScoringSupport.actual(c),
                matched?"expired citation expectation matched":"expired citation expectation mismatch");
    }
}
