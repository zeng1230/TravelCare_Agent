package travelcare_agent.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.trace.entity.*;
import travelcare_agent.trace.repository.*;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TraceQueryService {
    private static final Set<String> SPECIAL_EVENTS = Set.of("FALLBACK", "TIMEOUT", "HANDOFF_REQUIRED", "GUARDRAIL_BLOCKED");
    private final TraceRunRepository runs; private final TraceSpanRepository spans;
    private final TraceEventRepository events; private final TraceSnapshotRepository snapshots;
    private final ObjectMapper objectMapper;

    public TraceQueryService(TraceRunRepository runs, TraceSpanRepository spans, TraceEventRepository events,
            TraceSnapshotRepository snapshots, ObjectMapper objectMapper) {
        this.runs=runs; this.spans=spans; this.events=events; this.snapshots=snapshots; this.objectMapper=objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public TraceDetail get(String traceId) {
        TraceRun run=require(traceId);
        return new TraceDetail(run, spans.findByTraceId(traceId), events.findByTraceId(traceId), snapshots.findByTraceId(traceId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public TracePage bySession(Long sessionId,long pageNo,long pageSize) {
        long pn=pageNo<=0?1:pageNo, ps=pageSize<=0?20:Math.min(pageSize,100);
        return new TracePage(runs.findBySessionId(sessionId,pn,ps),runs.countBySessionId(sessionId),pn,ps);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public TraceDiagnostics diagnostics(String traceId) {
        TraceDetail detail=get(traceId); TraceRun run=detail.run();
        List<TraceSpan> retrieval=ofType(detail.spans(),"RETRIEVAL");
        List<TraceSpan> workflow=detail.spans().stream().filter(s->"WORKFLOW".equals(s.getSpanType())||"WORKFLOW_STEP".equals(s.getSpanType())).toList();
        List<TraceSpan> tools=ofType(detail.spans(),"TOOL"); List<TraceSpan> policies=ofType(detail.spans(),"POLICY");
        List<TraceEvent> special=detail.events().stream().filter(e->SPECIAL_EVENTS.contains(e.getEventType())).toList();
        List<TraceSpan> errors=detail.spans().stream().filter(s->"FAILED".equals(s.getStatus())||s.getErrorCode()!=null).toList();
        TraceSnapshot output=detail.snapshots().stream().filter(s->"FINAL_OUTPUT".equals(s.getSnapshotType())).reduce((a,b)->b).orElse(null);
        TraceSnapshot answerabilitySnapshot=detail.snapshots().stream().filter(s->"ANSWERABILITY_DECISION".equals(s.getSnapshotType())).reduce((a,b)->b).orElse(null);
        TraceSnapshot citationSnapshot=detail.snapshots().stream().filter(s->"CITATION_SUMMARY".equals(s.getSnapshotType())).reduce((a,b)->b).orElse(null);
        int redactions=detail.snapshots().stream().mapToInt(s->jsonInt(s.getRedactionSummaryJson(),"redactedCount")).sum();
        TraceSpan model=detail.spans().stream().filter(s->"MODEL".equals(s.getSpanType())||"FALLBACK".equals(s.getSpanType())).reduce((a,b)->b).orElse(null);
        return new TraceDiagnostics(traceId,run.getStatus(),run.getProvider()!=null?run.getProvider():jsonText(model,"provider"),
                run.getModel()!=null?run.getModel():jsonText(model,"model"),
                run.getPromptVersion()!=null?run.getPromptVersion():jsonText(model,"promptVersion"),
                retrieval,workflow,tools,policies,special,output,new RedactionSummary(redactions),errors,
                "RUNNING".equals(run.getStatus())||run.getFinishedAt()==null,
                answerability(answerabilitySnapshot), citations(citationSnapshot), rejectedCitationCandidates(citationSnapshot));
    }

    private TraceRun require(String id){return runs.findByTraceId(id).orElseThrow(()->new BusinessException(ResultCode.NOT_FOUND,"Trace not found: "+id));}
    private List<TraceSpan> ofType(List<TraceSpan> values,String type){return values.stream().filter(s->type.equals(s.getSpanType())).toList();}
    private String jsonText(TraceSpan span,String field){if(span==null||span.getMetadataJson()==null)return null;try{JsonNode n=objectMapper.readTree(span.getMetadataJson());return n.path(field).isMissingNode()?null:n.path(field).asText(null);}catch(Exception e){return null;}}
    private int jsonInt(String json,String field){if(json==null)return 0;try{return objectMapper.readTree(json).path(field).asInt(0);}catch(Exception e){return 0;}}
    private AnswerabilityDiagnostic answerability(TraceSnapshot snapshot){if(snapshot==null||snapshot.getPayloadJson()==null)return null;try{JsonNode n=objectMapper.readTree(snapshot.getPayloadJson());return new AnswerabilityDiagnostic(text(n,"status"),text(n,"reasonCode"),text(n,"requiredAction"),ids(n.path("evidenceChunkIds")),n.path("businessDecisionLocked").asBoolean(false),n.path("ragMayExplainBusinessDecision").asBoolean(false),n.path("ragMayOverrideBusinessDecision").asBoolean(false));}catch(Exception e){return null;}}
    private List<CitationDiagnostic> citations(TraceSnapshot snapshot){return citationList(snapshot,"citations",false);}
    private List<CitationDiagnostic> rejectedCitationCandidates(TraceSnapshot snapshot){return citationList(snapshot,"rejectedCitationCandidates",true);}
    private List<CitationDiagnostic> citationList(TraceSnapshot snapshot,String field,boolean rejected){if(snapshot==null||snapshot.getPayloadJson()==null)return List.of();try{JsonNode values=objectMapper.readTree(snapshot.getPayloadJson()).path(field);if(!values.isArray())return List.of();List<CitationDiagnostic> result=new ArrayList<>();for(JsonNode n:values){result.add(new CitationDiagnostic(text(n,"retrievalRunId"),longValue(n,"chunkId"),longValue(n,"documentId"),text(n,"title"),text(n,"sourceUri"),text(n,"effectiveFrom"),text(n,"effectiveTo"),rejected?text(n,"reasonCode"):null));}return result;}catch(Exception e){return List.of();}}
    private static String text(JsonNode n,String field){JsonNode v=n.path(field);return v.isMissingNode()||v.isNull()?null:v.asText();}
    private static Long longValue(JsonNode n,String field){JsonNode v=n.path(field);return v.isMissingNode()||v.isNull()?null:v.asLong();}
    private static List<Long> ids(JsonNode n){if(!n.isArray())return List.of();List<Long> values=new ArrayList<>();for(JsonNode v:n)values.add(v.asLong());return values;}

    public record TraceDetail(TraceRun run,List<TraceSpan> spans,List<TraceEvent> events,List<TraceSnapshot> snapshots){}
    public record TracePage(List<TraceRun> records,long total,long pageNo,long pageSize){}
    public record RedactionSummary(int redactedCount){}
    public record AnswerabilityDiagnostic(String status,String reasonCode,String requiredAction,List<Long> evidenceChunkIds,
            boolean businessDecisionLocked,boolean ragMayExplainBusinessDecision,boolean ragMayOverrideBusinessDecision){}
    public record CitationDiagnostic(String retrievalRunId,Long chunkId,Long documentId,String title,String sourceUri,
            String effectiveFrom,String effectiveTo,String reasonCode){}
    public record TraceDiagnostics(String traceId,String status,String provider,String model,String promptVersion,
            List<TraceSpan> retrievalSummary,List<TraceSpan> workflowPath,List<TraceSpan> toolCalls,
            List<TraceSpan> policyDecisions,List<TraceEvent> specialEvents,TraceSnapshot finalOutput,
            RedactionSummary redactionSummary,List<TraceSpan> errors,boolean incomplete,
            AnswerabilityDiagnostic answerability,List<CitationDiagnostic> citations,
            List<CitationDiagnostic> rejectedCitationCandidates){}
}
