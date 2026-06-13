package travelcare_agent.dryrun;

import org.springframework.stereotype.Service;
import travelcare_agent.trace.TraceEventType;
import travelcare_agent.trace.TraceService;

import java.util.Map;

@Service
public class SideEffectGuard {
    private static volatile TraceService recorder;
    private final TraceService traceService;

    public SideEffectGuard(TraceService traceService) {
        this.traceService = traceService;
        recorder = traceService;
    }

    public void check(SideEffectOperation operation) {
        checkCurrent(operation, traceService);
    }

    public static void checkCurrent(SideEffectOperation operation) {
        checkCurrent(operation, recorder);
    }

    private static void checkCurrent(SideEffectOperation operation, TraceService traceService) {
        DryRunContext context = DryRunContextHolder.current();
        if (context == null) return;
        if (traceService != null) traceService.recordEvent(context.dryRunTraceId(), null,
                TraceEventType.DRY_RUN_SKIPPED_SIDE_EFFECT, operation.name(), Map.of("operation", operation.name()));
        throw new DryRunSideEffectBlockedException(operation);
    }
}
