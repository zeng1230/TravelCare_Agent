package travelcare_agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.dryrun.*;
import travelcare_agent.evaluation.entity.*;
import travelcare_agent.evaluation.repository.*;
import travelcare_agent.evaluation.scoring.*;
import travelcare_agent.trace.*;
import travelcare_agent.trace.entity.*;
import travelcare_agent.trace.repository.TraceRunRepository;

import java.time.*;
import java.util.*;

@Service
public class EvaluationRunnerService {
    private final EvaluationDatasetRepository datasets; private final EvaluationCaseRepository cases;
    private final EvaluationRunRepository runs; private final EvaluationCaseResultRepository results;
    private final TraceRunRepository traceRuns; private final DryRunReadinessChecker readiness;
    private final DiagnosticDryRunService dryRun; private final TraceQueryService traces; private final TraceDiffService diffs;
    private final List<EvaluationScorer> scorers; private final EvaluationPromptStubRegistry stubs;
    private final EvaluationSideEffectGuard sideEffects; private final EvaluationRunReportWriter reports;
    private final BaselineComparisonService comparisons; private final ObjectMapper json; private final Clock clock;

    @Autowired
    public EvaluationRunnerService(EvaluationDatasetRepository d,EvaluationCaseRepository c,EvaluationRunRepository r,
            EvaluationCaseResultRepository cr,TraceRunRepository tr,DryRunReadinessChecker rd,DiagnosticDryRunService dr,
            TraceQueryService tq,TraceDiffService df,List<EvaluationScorer> s,EvaluationPromptStubRegistry st,
            EvaluationSideEffectGuard se,EvaluationRunReportWriter rw,BaselineComparisonService bc,ObjectMapper om){
        this(d,c,r,cr,tr,rd,dr,tq,df,s,st,se,rw,bc,om,Clock.systemDefaultZone());
    }
    EvaluationRunnerService(EvaluationDatasetRepository d,EvaluationCaseRepository c,EvaluationRunRepository r,
            EvaluationCaseResultRepository cr,TraceRunRepository tr,DryRunReadinessChecker rd,DiagnosticDryRunService dr,
            TraceQueryService tq,TraceDiffService df,List<EvaluationScorer> s,EvaluationPromptStubRegistry st,
            EvaluationSideEffectGuard se,EvaluationRunReportWriter rw,ObjectMapper om){
        this(d,c,r,cr,tr,rd,dr,tq,df,s,st,se,rw,null,om,Clock.systemDefaultZone());
    }
    EvaluationRunnerService(EvaluationDatasetRepository d,EvaluationCaseRepository c,EvaluationRunRepository r,
            EvaluationCaseResultRepository cr,TraceRunRepository tr,DryRunReadinessChecker rd,DiagnosticDryRunService dr,
            TraceQueryService tq,TraceDiffService df,List<EvaluationScorer> s,EvaluationPromptStubRegistry st,
            EvaluationSideEffectGuard se,EvaluationRunReportWriter rw,ObjectMapper om,Clock clock){
        this(d,c,r,cr,tr,rd,dr,tq,df,s,st,se,rw,null,om,clock);
    }
    EvaluationRunnerService(EvaluationDatasetRepository d,EvaluationCaseRepository c,EvaluationRunRepository r,
            EvaluationCaseResultRepository cr,TraceRunRepository tr,DryRunReadinessChecker rd,DiagnosticDryRunService dr,
            TraceQueryService tq,TraceDiffService df,List<EvaluationScorer> s,EvaluationPromptStubRegistry st,
            EvaluationSideEffectGuard se,EvaluationRunReportWriter rw,BaselineComparisonService bc,ObjectMapper om,Clock clock){
        datasets=d;cases=c;runs=r;results=cr;traceRuns=tr;readiness=rd;dryRun=dr;traces=tq;diffs=df;scorers=s;stubs=st;sideEffects=se;reports=rw;comparisons=bc;json=om;this.clock=clock;
    }

    public EvaluationRun start(Long datasetId,String provider,String stub){return start(datasetId,provider,stub,true);}
    public EvaluationRun start(Long datasetId,String provider,String stub,boolean compareWithBaseline){
        EvaluationDataset ds=datasets.findById(datasetId).orElseThrow(()->new BusinessException(ResultCode.EVALUATION_DATASET_NOT_FOUND));
        if(!"ACTIVE".equals(ds.getStatus()))throw new BusinessException(ResultCode.EVALUATION_DATASET_NOT_ACTIVE);
        String p=provider==null?"mock":provider;if(!"mock".equals(p))throw new BusinessException(ResultCode.EVALUATION_PROVIDER_NOT_ALLOWED);
        String sv=stub==null?stubs.defaultVersion():stub;if(!stubs.contains(sv))throw new BusinessException(ResultCode.EVALUATION_PROMPT_STUB_UNKNOWN);
        List<EvaluationCase> enabled=cases.findEnabledCasesByDatasetId(datasetId);if(enabled.isEmpty())throw new BusinessException(ResultCode.EVALUATION_EMPTY_DATASET);
        EvaluationRun run=new EvaluationRun();run.setDatasetId(datasetId);run.setDatasetVersion(ds.getVersion());run.setProviderMode("mock");run.setPromptStubVersion(sv);run.setStatus("PENDING");run.setTotalCount(enabled.size());run.setPassedCount(0);run.setFailedCount(0);run.setErrorCount(0);run.setSkippedCount(0);run.setRegressionStatus("NOT_COMPARED");run.setRegressionCount(0);run.setImprovedCount(0);run.setNewCaseCount(0);run.setMissingCaseCount(0);run.setConfigJson(write(Map.of("providerMode","mock","promptStubVersion",sv,"compareWithBaseline",compareWithBaseline)));run.setCreatedAt(now());runs.save(run);
        run.setStatus("RUNNING");run.setStartedAt(now());runs.save(run);
        Map<Long,List<ScoreResult>> allScores=new LinkedHashMap<>();for(EvaluationCase c:enabled)execute(run,c,allScores);
        aggregate(run);run.setFinishedAt(now());run.setSummaryJson(write(summary(run,null,null,null,compareWithBaseline&&!hasBaseline(ds)?"No baseline promoted for this dataset version":null)));runs.save(run);
        String comparisonError=null;
        if(compareWithBaseline&&hasBaseline(ds)&&comparisons!=null){try{run=comparisons.compare(run.getId(),ds.getCurrentBaselineRunId());}catch(Exception ex){comparisonError=ex.getClass().getSimpleName()+": "+ex.getMessage();run=runs.findById(run.getId()).orElse(run);run.setBaselineRunId(ds.getCurrentBaselineRunId());run.setRegressionStatus("PARTIAL");run.setSummaryJson(write(summary(run,null,null,comparisonError,null)));runs.save(run);}}
        String reportError=null,path=null;try{path=reports.write(run,ds,enabled,results.findResultsByRunId(run.getId()),allScores,clock).toString();}catch(Exception ex){reportError=ex.getClass().getSimpleName()+": "+ex.getMessage();}
        run.setSummaryJson(write(summary(run,path,reportError,comparisonError,compareWithBaseline&&!hasBaseline(ds)?"No baseline promoted for this dataset version":null)));return runs.save(run);
    }
    private boolean hasBaseline(EvaluationDataset ds){return ds.getCurrentBaselineRunId()!=null;}
    private void execute(EvaluationRun run,EvaluationCase c,Map<Long,List<ScoreResult>> allScores){EvaluationCaseResult out=base(run,c);Map<String,Long> before=sideEffects.snapshot();try{TraceRun source=traceRuns.findById(c.getSourceTraceId()).orElseThrow(()->new IllegalArgumentException("source trace not found"));DryRunReadinessResult ready=readiness.check(source.getTraceId(),"mock");if(!ready.ready()){out.setStatus("SKIPPED");out.setFailureReason("missing: "+ready.missingSnapshots());return;}DryRunResult dr=dryRun.runForEvaluation(source.getTraceId(),new DryRunRequest("evaluation:"+run.getId()+":"+c.getCaseKey(),"mock",true),run.getPromptStubVersion(),(answer,decision)->stubs.render(run.getPromptStubVersion(),answer,decision));if(!"SUCCEEDED".equals(dr.status())){out.setStatus("ERROR");out.setFailureReason("dry run failed: "+dr.code());return;}TraceRun dry=traceRuns.findByTraceId(dr.dryRunTraceId()).orElseThrow();out.setDryRunTraceId(dry.getId());out.setDiffId(dr.diffId());out.setRiskLevel(dr.riskLevel());TraceQueryService.TraceDetail detail=traces.get(dr.dryRunTraceId());EvaluationScoringContext ctx=context(run,c,dr,detail,sideEffects.compare(before));List<ScoreResult> scoreResults=new ArrayList<>();for(EvaluationScorer scorer:scorers){try{scoreResults.add(scorer.score(ctx));}catch(Exception ex){throw new ScorerFailure(scorer.name(),ex);}}allScores.put(c.getId(),scoreResults);out.setScoresJson(write(scoreResults));if(scoreResults.stream().noneMatch(ScoreResult::applied)){out.setStatus("ERROR");out.setFailureReason("no applicable scorer");}else if(scoreResults.stream().anyMatch(x->x.applied()&&!x.passed())){out.setStatus("FAILED");out.setFailureReason(scoreResults.stream().filter(x->x.applied()&&!x.passed()).map(ScoreResult::reason).reduce((a,b)->a+"; "+b).orElse(null));}else out.setStatus("PASSED");}catch(ScorerFailure ex){out.setStatus("ERROR");out.setFailureReason("scorer "+ex.scorer+": "+ex.getCause().getClass().getSimpleName());}catch(Exception ex){out.setStatus("ERROR");out.setFailureReason(ex.getClass().getSimpleName()+": "+ex.getMessage());}finally{out.setFinishedAt(now());results.save(out);}}
    private EvaluationScoringContext context(EvaluationRun run,EvaluationCase c,DryRunResult dr,TraceQueryService.TraceDetail d,SideEffectCheckResult se)throws Exception{EvaluationScoringContext x=new EvaluationScoringContext();x.run=run;x.evaluationCase=c;x.expectation=json.readTree(c.getExpectationJson());x.sourceTraceId=c.getSourceTraceId();x.dryRunTraceId=traceRuns.findByTraceId(dr.dryRunTraceId()).map(TraceRun::getId).orElse(null);x.diffId=dr.diffId();x.dryRunResult=dr;x.sideEffectCheckResult=se;x.promptStubVersion=run.getPromptStubVersion();x.providerMode=run.getProviderMode();x.clock=clock;x.riskLevel=dr.riskLevel();x.spanTypes=d.spans().stream().map(TraceSpan::getSpanType).distinct().toList();List<String> names=new ArrayList<>();d.events().forEach(e->{names.add(e.getEventType());names.add(e.getName());});d.snapshots().forEach(s->names.add(s.getSnapshotType()));x.eventNames=names;x.policyDecision=snapshotText(d,"POLICY_DECISION","decision");x.workflowStatus=snapshotText(d,"WORKFLOW_PATH","status");x.output=snapshotText(d,"FINAL_OUTPUT","answer");return x;}
    private String snapshotText(TraceQueryService.TraceDetail d,String type,String field){return d.snapshots().stream().filter(s->type.equals(s.getSnapshotType())).reduce((a,b)->b).map(s->{try{JsonNode n=json.readTree(s.getPayloadJson());return n.path(field).asText(null);}catch(Exception e){return null;}}).orElse(null);}
    private EvaluationCaseResult base(EvaluationRun r,EvaluationCase c){EvaluationCaseResult v=new EvaluationCaseResult();v.setRunId(r.getId());v.setCaseId(c.getId());v.setCaseKey(c.getCaseKey());v.setSourceTraceId(c.getSourceTraceId());v.setRegressionStatus("NOT_COMPARED");v.setStartedAt(now());return v;}
    private void aggregate(EvaluationRun r){int p=(int)results.countByRunIdAndStatus(r.getId(),"PASSED"),f=(int)results.countByRunIdAndStatus(r.getId(),"FAILED"),e=(int)results.countByRunIdAndStatus(r.getId(),"ERROR"),s=(int)results.countByRunIdAndStatus(r.getId(),"SKIPPED");r.setPassedCount(p);r.setFailedCount(f);r.setErrorCount(e);r.setSkippedCount(s);r.setStatus(e>0?"PARTIAL":f>0?"FAILED":s>0?"PARTIAL":p>0?"PASSED":"PARTIAL");}
    private Map<String,Object> summary(EvaluationRun r,String path,String reportError,String comparisonError,String regressionReason){Map<String,Object> m=new LinkedHashMap<>();m.put("totalCount",r.getTotalCount());m.put("passedCount",r.getPassedCount());m.put("failedCount",r.getFailedCount());m.put("errorCount",r.getErrorCount());m.put("skippedCount",r.getSkippedCount());m.put("regressionStatus",r.getRegressionStatus());m.put("regressionCount",r.getRegressionCount());m.put("improvedCount",r.getImprovedCount());m.put("newCaseCount",r.getNewCaseCount());m.put("missingCaseCount",r.getMissingCaseCount());if(path!=null)m.put("reportPath",path);if(reportError!=null)m.put("reportError",reportError);if(comparisonError!=null)m.put("comparisonError",comparisonError);if(regressionReason!=null)m.put("regressionReason",regressionReason);return m;}
    private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception ex){throw new IllegalStateException(ex);}} private LocalDateTime now(){return LocalDateTime.now(clock);} private static class ScorerFailure extends RuntimeException{final String scorer;ScorerFailure(String s,Throwable t){super(t);scorer=s;}}
    public EvaluationRun get(Long id){return runs.findById(id).orElseThrow(()->new BusinessException(ResultCode.EVALUATION_RUN_NOT_FOUND));} public List<EvaluationCaseResult> results(Long id){get(id);return results.findResultsByRunId(id);} public String report(Long id){get(id);try{return reports.read(id);}catch(Exception e){throw new BusinessException(ResultCode.EVALUATION_REPORT_NOT_FOUND);}}
}
