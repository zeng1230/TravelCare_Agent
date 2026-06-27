package travelcare_agent.api;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import travelcare_agent.common.result.PageResult;
import travelcare_agent.common.result.Result;
import travelcare_agent.trace.TraceQueryService;
import travelcare_agent.trace.entity.TraceRun;
import travelcare_agent.dryrun.*;
import travelcare_agent.common.result.ResultCode;

@RestController
@RequestMapping("/api/agent-traces")
public class AgentTraceController {
    private final TraceQueryService service;
    private final DiagnosticDryRunService dryRunService;
    private final TraceDiffService diffService;
    public AgentTraceController(TraceQueryService service,DiagnosticDryRunService dryRunService,TraceDiffService diffService){this.service=service;this.dryRunService=dryRunService;this.diffService=diffService;}

    @GetMapping("/{traceId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<TraceQueryService.TraceDetail> get(@PathVariable String traceId){return Result.success(service.get(traceId));}

    @GetMapping("/by-session/{sessionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<TraceRun>> bySession(@PathVariable Long sessionId,
            @RequestParam(defaultValue="1") long pageNo,@RequestParam(defaultValue="20") long pageSize){
        TraceQueryService.TracePage page=service.bySession(sessionId,pageNo,pageSize);
        return Result.success(PageResult.of(page.records(),page.total(),page.pageNo(),page.pageSize()));
    }

    @GetMapping("/{traceId}/diagnostics")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<TraceQueryService.TraceDiagnostics> diagnostics(@PathVariable String traceId){return Result.success(service.diagnostics(traceId));}

    @PostMapping("/{traceId}/dry-run")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<DryRunResult> dryRun(@PathVariable String traceId,@RequestBody(required=false) DryRunRequest request){
        DryRunRequest effective=request==null?new DryRunRequest("manual-debug","mock",true):new DryRunRequest(
                request.reason()==null?"manual-debug":request.reason(),request.providerMode()==null?"mock":request.providerMode(),request.compareAfterRun());
        DryRunResult result=dryRunService.run(traceId,effective);
        return Result.of("DRY_RUN_NOT_READY".equals(result.code())?ResultCode.DRY_RUN_NOT_READY:ResultCode.SUCCESS,result);
    }

    @GetMapping("/{traceId}/diffs/{dryRunTraceId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<TraceDiffResult> diff(@PathVariable String traceId,@PathVariable String dryRunTraceId){return Result.success(diffService.get(traceId,dryRunTraceId));}
}
