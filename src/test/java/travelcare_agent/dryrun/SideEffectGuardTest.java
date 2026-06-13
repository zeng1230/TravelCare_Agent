package travelcare_agent.dryrun;

import org.junit.jupiter.api.Test;
import travelcare_agent.trace.TraceEventType;
import travelcare_agent.trace.TraceService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SideEffectGuardTest {
    @Test
    void blocksAndRecordsAnySideEffectInsideDryRunContext() {
        TraceService traceService = mock(TraceService.class);
        try (DryRunContextHolder.Scope ignored = DryRunContextHolder.attach(
                new DryRunContext("original", "dry", "manual-debug", "mock"))) {
            assertThatThrownBy(() -> new SideEffectGuard(traceService).check(SideEffectOperation.RABBITMQ_PUBLISH))
                    .isInstanceOf(DryRunSideEffectBlockedException.class);
        }
        verify(traceService).recordEvent(eq("dry"), isNull(), eq(TraceEventType.DRY_RUN_SKIPPED_SIDE_EFFECT),
                eq("RABBITMQ_PUBLISH"), anyMap());
    }
}
