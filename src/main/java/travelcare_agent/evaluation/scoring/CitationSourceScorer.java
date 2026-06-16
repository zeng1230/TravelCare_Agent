package travelcare_agent.evaluation.scoring;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class CitationSourceScorer implements EvaluationScorer {
    public String name(){return "citationSource";}
    public ScoreResult score(EvaluationScoringContext c){
        var expectedNode=Stage9ScoringSupport.expectation(c,"expectedCitationChunkIds");
        if(expectedNode==null||!expectedNode.isArray())return ScoreResult.skipped(name());
        if(!Stage9ScoringSupport.hasStage9Snapshots(c))return ScoreResult.skipped(name());
        List<Long> expected=Stage9ScoringSupport.longList(expectedNode);
        List<Long> actual=Stage9ScoringSupport.chunkIds(c.citations());
        List<Long> missing=expected.stream().filter(id->!actual.contains(id)).toList();
        return ScoreResult.of(name(),missing.isEmpty(),expected,Stage9ScoringSupport.actual(c),
                missing.isEmpty()?"citation chunk ids matched":"missing citation chunk ids: "+missing);
    }
}
