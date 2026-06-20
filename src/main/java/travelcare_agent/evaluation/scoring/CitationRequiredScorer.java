package travelcare_agent.evaluation.scoring;

import org.springframework.stereotype.Component;

@Component
public class CitationRequiredScorer implements EvaluationScorer {
    public String name(){return "citationRequired";}
    public ScoreResult score(EvaluationScoringContext c){
        Boolean expected=Stage9ScoringSupport.expectation(c).expectCitationRequired();
        if(expected==null)return ScoreResult.skipped(name());
        if(!Stage9ScoringSupport.hasStage9Snapshots(c))return ScoreResult.skipped(name());
        boolean present=!Stage9ScoringSupport.chunkIds(c.citations()).isEmpty();
        boolean matched=expected==present;
        return ScoreResult.of(name(),matched,expected,Stage9ScoringSupport.actual(c),
                matched?"citation requirement matched":"citation presence did not match expectation");
    }
}
