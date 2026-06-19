package travelcare_agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import travelcare_agent.evaluation.entity.*;
import travelcare_agent.evaluation.scoring.ScoreResult;
import java.nio.file.*;
import java.time.*;
import java.util.*;

@Component
public class EvaluationRunReportWriter {
    private final Path root; private final ObjectMapper json=new ObjectMapper();
    public EvaluationRunReportWriter(){this(Path.of("target","evaluation","runs"));} public EvaluationRunReportWriter(Path root){this.root=root;}
    public Path path(Long id){return root.resolve(id+"_report.md");}
    public Path write(EvaluationRun run,EvaluationDataset dataset,List<EvaluationCase> cases,List<EvaluationCaseResult> results,Map<Long,List<ScoreResult>> scores,Clock clock)throws Exception{
        Files.createDirectories(root);Map<Long,EvaluationCase> byId=new HashMap<>();cases.forEach(c->byId.put(c.getId(),c));StringBuilder b=new StringBuilder("# Evaluation Run ").append(run.getId()).append("\n\n");
        line(b,"Run ID",run.getId());line(b,"Dataset key",dataset.getDatasetKey());line(b,"Dataset version",run.getDatasetVersion());line(b,"Provider mode",run.getProviderMode());line(b,"Prompt stub version",run.getPromptStubVersion());line(b,"Run status",run.getStatus());line(b,"Baseline Run ID",run.getBaselineRunId());line(b,"Regression Status",run.getRegressionStatus());line(b,"Regression Count",run.getRegressionCount());line(b,"Improved Count",run.getImprovedCount());line(b,"New Case Count",run.getNewCaseCount());line(b,"Missing Case Count",run.getMissingCaseCount());line(b,"Total count",run.getTotalCount());line(b,"Passed count",run.getPassedCount());line(b,"Failed count",run.getFailedCount());line(b,"Error count",run.getErrorCount());line(b,"Skipped count",run.getSkippedCount());line(b,"Started at",run.getStartedAt());line(b,"Finished at",run.getFinishedAt());line(b,"Report generated at",LocalDateTime.now(clock));
        if(run.getBaselineRunId()==null)b.append("\nReason: No baseline promoted for this dataset version\n");
        List<EvaluationCaseResult> regressions=results.stream().filter(r->"REGRESSION".equals(r.getRegressionStatus())).toList();if(!regressions.isEmpty()){b.append("\n## Regressions\n\n");for(EvaluationCaseResult r:regressions)b.append("- ").append(r.getCaseKey()).append(": ").append(summary(r)).append("\n");}
        List<EvaluationCaseResult> high=results.stream().filter(this::highRiskRegression).toList();if(!high.isEmpty()){b.append("\n## High Risk Regressions\n\n");for(EvaluationCaseResult r:high)b.append("- ").append(r.getCaseKey()).append(": ").append(summary(r)).append("\n");}
        for(EvaluationCaseResult r:results){EvaluationCase c=byId.get(r.getCaseId());b.append("\n## Case ").append(r.getCaseKey()).append("\n\n");line(b,"caseKey",r.getCaseKey());line(b,"caseName",c==null?null:c.getName());line(b,"sourceTraceId",r.getSourceTraceId());line(b,"dryRunTraceId",r.getDryRunTraceId());line(b,"diffId",r.getDiffId());line(b,"status",r.getStatus());line(b,"riskLevel",r.getRiskLevel());line(b,"failureReason",r.getFailureReason());line(b,"baselineCaseResultId",r.getBaselineCaseResultId());line(b,"regressionStatus",r.getRegressionStatus());line(b,"regressionReasonJson",r.getRegressionReasonJson());b.append("- scorer results: `").append(scores.getOrDefault(r.getCaseId(),List.of())).append("`\n");}
        Files.writeString(path(run.getId()),b.toString());return path(run.getId());
    }
    public String read(Long id)throws Exception{return Files.readString(path(id));}
    private boolean highRiskRegression(EvaluationCaseResult r){if(!"REGRESSION".equals(r.getRegressionStatus())||r.getRegressionReasonJson()==null)return false;try{JsonNode n=json.readTree(r.getRegressionReasonJson());String risk=n.path("highestRisk").asText();return "HIGH".equals(risk)||"CRITICAL".equals(risk)||contains(n.path("changedFields"),"policyDecision")||contains(n.path("changedFields"),"workflowStatus")||contains(n.path("changedFields"),"sideEffectSafetyPassed");}catch(Exception e){return false;}}
    private boolean contains(JsonNode values,String expected){for(JsonNode v:values)if(expected.equals(v.asText()))return true;return false;}
    private String summary(EvaluationCaseResult r){try{return json.readTree(r.getRegressionReasonJson()).path("summary").asText();}catch(Exception e){return "Regression detected";}}
    private void line(StringBuilder b,String k,Object v){b.append("- ").append(k).append(": ").append(v==null?"":v).append("\n");}
}
