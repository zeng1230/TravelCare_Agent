package travelcare_agent.evaluation.scoring;

import org.springframework.stereotype.Component;

@Component
public class CitationRequiredScorer implements EvaluationScorer {
    public String name(){return "citationRequired";}
    public ScoreResult score(EvaluationScoringContext c){
        var expected=Stage9ScoringSupport.expectation(c,"requireCitation");
        if(expected==null||!expected.asBoolean(false))return ScoreResult.skipped(name());
        if(!Stage9ScoringSupport.hasStage9Snapshots(c))return ScoreResult.skipped(name());
        boolean present=!Stage9ScoringSupport.chunkIds(c.citations()).isEmpty();
        return ScoreResult.of(name(),present,true,Stage9ScoringSupport.actual(c),
                present?"citation present":"citation required but no final citation was present");
    }
}
