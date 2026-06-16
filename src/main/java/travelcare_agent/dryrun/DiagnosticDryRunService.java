package travelcare_agent.dryrun;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import travelcare_agent.adapter.order.MockOrderAdapter;
import travelcare_agent.policy.RefundEligibilityPolicy;
import travelcare_agent.trace.SpanType;
import travelcare_agent.trace.TraceContextHolder;
import travelcare_agent.trace.TraceQueryService;
import travelcare_agent.trace.TraceService;
import travelcare_agent.trace.TraceSnapshotType;
import travelcare_agent.trace.entity.TraceSnapshot;
import travelcare_agent.workflow.workflows.OrderRefundInquiryWorkflow;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@Service
public class DiagnosticDryRunService {
    private final DryRunReadinessChecker readinessChecker;
    private final TraceService traceService;
    private final TraceQueryService traceQueryService;
    private final SnapshotToolExecutor snapshotToolExecutor;
    private final SnapshotRetrievalExecutor snapshotRetrievalExecutor;
    private final DryRunModelExecutor dryRunModelExecutor;
    private final DryRunWorkflowSimulator workflowSimulator;
    private final RefundEligibilityPolicy refundEligibilityPolicy;
    private final TraceDiffService traceDiffService;
    private final ObjectMapper objectMapper;

    public DiagnosticDryRunService(DryRunReadinessChecker readinessChecker, TraceService traceService,
            TraceQueryService traceQueryService, SnapshotToolExecutor snapshotToolExecutor,
            SnapshotRetrievalExecutor snapshotRetrievalExecutor, DryRunModelExecutor dryRunModelExecutor,
            DryRunWorkflowSimulator workflowSimulator, RefundEligibilityPolicy refundEligibilityPolicy,
            TraceDiffService traceDiffService, ObjectMapper objectMapper) {
        this.readinessChecker = readinessChecker;
        this.traceService = traceService;
        this.traceQueryService=traceQueryService;this.snapshotToolExecutor=snapshotToolExecutor;
        this.snapshotRetrievalExecutor=snapshotRetrievalExecutor;this.dryRunModelExecutor=dryRunModelExecutor;
        this.workflowSimulator=workflowSimulator;this.refundEligibilityPolicy=refundEligibilityPolicy;
        this.traceDiffService = traceDiffService;
        this.objectMapper=objectMapper;
    }

    public DryRunResult run(String originalTraceId, DryRunRequest request) {
        return runInternal(originalTraceId, request, "stage7b-dry-run", (answer, decision) -> answer);
    }

    public DryRunResult runForEvaluation(String originalTraceId, DryRunRequest request,
            String promptVersion, BiFunction<String, String, String> responseRenderer) {
        return runInternal(originalTraceId, request, promptVersion, responseRenderer);
    }

    private DryRunResult runInternal(String originalTraceId, DryRunRequest request,
            String promptVersion, BiFunction<String, String, String> responseRenderer) {
        DryRunReadinessResult readiness = readinessChecker.check(originalTraceId, request.providerMode());
        if (!readiness.ready()) return DryRunResult.rejected(originalTraceId, readiness);
        TraceQueryService.TraceDetail original=traceQueryService.get(originalTraceId);
        TraceService.RootTrace root=traceService.startDryRunRoot(original.run().getSessionId(),original.run().getUserId(),
                originalTraceId,request.reason(),request.providerMode(),Map.of("schemaVersion","7B"));
        if(!root.available())return DryRunResult.failed(originalTraceId,null,"DRY_RUN_TRACE_UNAVAILABLE");
        Map<String,TraceSnapshot> snapshots=original.snapshots().stream().collect(Collectors.toMap(
                TraceSnapshot::getSnapshotType,Function.identity(),(left,right)->right,LinkedHashMap::new));
        try(DryRunContextHolder.Scope dryScope=DryRunContextHolder.attach(new DryRunContext(originalTraceId,root.traceId(),request.reason(),request.providerMode()));
                TraceContextHolder.Scope traceScope=TraceContextHolder.attach(root.traceId(),root.rootSpanId())){
            copy(root,snapshots,TraceSnapshotType.USER_INPUT);
            copy(root,snapshots,TraceSnapshotType.CONTEXT_SUMMARY);
            copyOptional(root,snapshots,TraceSnapshotType.ANSWERABILITY_DECISION.name());
            copyOptional(root,snapshots,TraceSnapshotType.CITATION_SUMMARY.name());

            TraceService.SpanHandle retrieval=traceService.startSpan(SpanType.RETRIEVAL,"snapshot-retrieval",Map.of("source","snapshot"));
            var retrievalSummary=snapshotRetrievalExecutor.execute(snapshots.get(TraceSnapshotType.RETRIEVAL_SUMMARY.name()));
            traceService.recordSnapshot(root.traceId(),retrieval.spanId(),TraceSnapshotType.RETRIEVAL_SUMMARY.name(),"TRACE_SNAPSHOT",originalTraceId,retrievalSummary);
            traceService.finishSpanSuccess(retrieval,"TRACE_SNAPSHOT:"+originalTraceId,Map.of("source","snapshot"));

            TraceService.SpanHandle tool=traceService.startSpan(SpanType.TOOL,"snapshot-GetOrderTool",Map.of("source","snapshot"));
            copy(root,snapshots,TraceSnapshotType.TOOL_REQUEST);
            MockOrderAdapter.OrderSnapshot order=snapshotToolExecutor.order(snapshots.get(TraceSnapshotType.TOOL_RESULT.name()));
            traceService.recordSnapshot(root.traceId(),tool.spanId(),TraceSnapshotType.TOOL_RESULT.name(),"TRACE_SNAPSHOT",originalTraceId,
                    objectMapper.readTree(snapshots.get(TraceSnapshotType.TOOL_RESULT.name()).getPayloadJson()));
            traceService.finishSpanSuccess(tool,"TRACE_SNAPSHOT:"+originalTraceId,Map.of("status","SUCCEEDED","source","snapshot"));

            var policyInput=objectMapper.readTree(snapshots.get(TraceSnapshotType.POLICY_INPUT.name()).getPayloadJson());
            LocalDateTime evaluatedAt=objectMapper.treeToValue(policyInput.path("evaluatedAt"),LocalDateTime.class);
            Long currentUserId=policyInput.path("currentUserId").asLong();
            OrderRefundInquiryWorkflow.OrderSnapshot policyOrder=OrderRefundInquiryWorkflow.OrderSnapshot.of(
                    order.orderId(),order.orderNo(),order.userId(),order.status(),order.refundable(),order.paidAmount(),order.departureTime());
            var decision=refundEligibilityPolicy.evaluateAt(policyOrder,currentUserId,evaluatedAt);

            TraceService.SpanHandle workflow=traceService.startSpan(SpanType.WORKFLOW,"dry-run-order-refund-inquiry",Map.of("source","simulator"));
            DryRunWorkflowSimulator.Simulation simulation=workflowSimulator.simulate(order,decision);
            traceService.recordSnapshot(root.traceId(),workflow.spanId(),TraceSnapshotType.WORKFLOW_PATH.name(),"DRY_RUN",root.traceId(),Map.of(
                    "workflowType","order_refund_inquiry","status",simulation.status(),"steps",simulation.steps()));
            traceService.finishSpanSuccess(workflow,"DRY_RUN_WORKFLOW",Map.of("status",simulation.status()));

            TraceService.SpanHandle model=traceService.startSpan(SpanType.MODEL,"dry-run-response-generation",Map.of("provider","mock"));
            traceService.recordSnapshot(root.traceId(),model.spanId(),TraceSnapshotType.MODEL_INPUT.name(),"MODEL_OPERATION","RESPONSE_GENERATION",Map.of(
                    "operation","RESPONSE_GENERATION","promptVersion",promptVersion,"input",Map.of("deterministicAnswer",simulation.deterministicAnswer())));
            String renderedAnswer=responseRenderer.apply(simulation.deterministicAnswer(),decision.status().name());
            DryRunModelExecutor.ModelResult generated=dryRunModelExecutor.generate(renderedAnswer,promptVersion);
            traceService.recordSnapshot(root.traceId(),model.spanId(),TraceSnapshotType.MODEL_OUTPUT.name(),"MODEL_OPERATION","RESPONSE_GENERATION",Map.of(
                    "operation","RESPONSE_GENERATION","provider",generated.provider(),"model",generated.model(),"promptVersion",promptVersion,"output",generated.output()));
            traceService.finishSpanSuccess(model,null,Map.of("provider",generated.provider(),"model",generated.model(),"promptVersion",promptVersion));

            Map<String,Object> finalOutput=new LinkedHashMap<>();finalOutput.put("answer",generated.answer());
            Boolean fallbackUsed=originalFallbackUsed(snapshots.get(TraceSnapshotType.FINAL_OUTPUT.name()));
            if(fallbackUsed!=null)finalOutput.put("fallbackUsed",fallbackUsed);
            traceService.finishRootRunSuccess(root.traceId(),null,null,finalOutput);
            TraceDiffResult diff=request.compareAfterRun()?traceDiffService.create(originalTraceId,root.traceId()):null;
            return DryRunResult.succeeded(originalTraceId,root.traceId(),diff);
        }catch(DryRunSideEffectBlockedException ex){
            traceService.finishRootRunFailure(root.traceId(),"DRY_RUN_SIDE_EFFECT_BLOCKED",ex);
            return DryRunResult.failed(originalTraceId,root.traceId(),"DRY_RUN_SIDE_EFFECT_BLOCKED");
        }catch(Exception ex){
            traceService.finishRootRunFailure(root.traceId(),"DRY_RUN_FAILED",ex);
            return DryRunResult.failed(originalTraceId,root.traceId(),"DRY_RUN_FAILED");
        }
    }

    private void copy(TraceService.RootTrace root,Map<String,TraceSnapshot> snapshots,TraceSnapshotType type)throws Exception{
        TraceSnapshot snapshot=snapshots.get(type.name());traceService.recordSnapshot(root.traceId(),root.rootSpanId(),type.name(),"TRACE_SNAPSHOT",snapshot.getTraceId(),objectMapper.readTree(snapshot.getPayloadJson()));
    }
    private void copyOptional(TraceService.RootTrace root,Map<String,TraceSnapshot> snapshots,String type)throws Exception{
        TraceSnapshot snapshot=snapshots.get(type);if(snapshot!=null)traceService.recordSnapshot(root.traceId(),root.rootSpanId(),type,"TRACE_SNAPSHOT",snapshot.getTraceId(),objectMapper.readTree(snapshot.getPayloadJson()));
    }
    private Boolean originalFallbackUsed(TraceSnapshot snapshot){
        if(snapshot==null||snapshot.getPayloadJson()==null)return null;
        try{var node=objectMapper.readTree(snapshot.getPayloadJson());return node.has("fallbackUsed")?node.path("fallbackUsed").asBoolean(false):null;}catch(Exception e){return null;}
    }
}
