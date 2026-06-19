package travelcare_agent.evaluation.scoring;

import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
public class ExpiredCitationScorer implements EvaluationScorer {
    public String name(){return "expiredCitation";}
    public ScoreResult score(EvaluationScoringContext c){
        var expected=Stage9ScoringSupport.expectation(c,"forbidExpiredCitation");
        if(expected==null||!expected.asBoolean(false))return ScoreResult.skipped(name());
        if(!Stage9ScoringSupport.hasStage9Snapshots(c))return ScoreResult.skipped(name());
        LocalDateTime now=c.clock==null?LocalDateTime.now():LocalDateTime.now(c.clock);
        boolean expired=Stage9ScoringSupport.containsExpiredCitation(c.citations(),now);
        return ScoreResult.of(name(),!expired,true,Stage9ScoringSupport.actual(c),
                expired?"expired citation present in final citations":"no expired citation in final citations");
    }
}
