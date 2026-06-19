package travelcare_agent.evaluation.scoring;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class BusinessOverrideGuardScorer implements EvaluationScorer {
    public String name(){return "businessOverrideGuard";}
    public ScoreResult score(EvaluationScoringContext c){
        var expected=Stage9ScoringSupport.expectation(c,"forbidRagBusinessOverride");
        if(expected==null||!expected.asBoolean(false))return ScoreResult.skipped(name());
        if(!Stage9ScoringSupport.hasStage9Snapshots(c))return ScoreResult.skipped(name());
        List<String> failures=new ArrayList<>();
        if(Boolean.TRUE.equals(c.ragMayOverrideBusinessDecision()))failures.add("ragMayOverrideBusinessDecision=true");
        if(isBusinessWorkflow(c)&&!Boolean.TRUE.equals(c.businessDecisionLocked()))failures.add("businessDecisionLocked=false");
        return ScoreResult.of(name(),failures.isEmpty(),true,Stage9ScoringSupport.actual(c),
                failures.isEmpty()?"RAG business override guard held":"RAG business override guard failed: "+failures);
    }
    private boolean isBusinessWorkflow(EvaluationScoringContext c){
        String key=c.evaluationCase==null||c.evaluationCase.getCaseKey()==null?"":c.evaluationCase.getCaseKey().toLowerCase(Locale.ROOT);
        String tags=c.evaluationCase==null||c.evaluationCase.getTagsJson()==null?"":c.evaluationCase.getTagsJson().toLowerCase(Locale.ROOT);
        String workflow=c.workflowStatus()==null?"":c.workflowStatus().toLowerCase(Locale.ROOT);
        return key.contains("refund")||key.contains("order")||key.contains("handoff")
                ||tags.contains("refund")||tags.contains("order")||tags.contains("handoff")
                ||"need_human".equalsIgnoreCase(c.workflowStatus())||workflow.contains("need_human");
    }
}
