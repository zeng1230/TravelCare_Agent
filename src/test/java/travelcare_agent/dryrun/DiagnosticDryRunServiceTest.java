package travelcare_agent.dryrun;

import org.junit.jupiter.api.Test;
import travelcare_agent.trace.TraceService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DiagnosticDryRunServiceTest {

    @Test
    void readinessRejectionDoesNotCreateDryRunTraceOrDiff() {
        DryRunReadinessChecker readiness = mock(DryRunReadinessChecker.class);
        TraceService traceService = mock(TraceService.class);
        TraceDiffService diffService = mock(TraceDiffService.class);
        when(readiness.check("legacy", "mock")).thenReturn(DryRunReadinessResult.rejected(List.of("TOOL_RESULT")));
        DiagnosticDryRunService service = new DiagnosticDryRunService(
                readiness, traceService, null, null, null, null, null, null, diffService, null
        );

        DryRunResult result = service.run("legacy", new DryRunRequest("manual-debug", "mock", true));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.code()).isEqualTo("DRY_RUN_NOT_READY");
        assertThat(result.dryRunTraceId()).isNull();
        assertThat(result.diffId()).isNull();
        assertThat(result.allowedActions()).containsExactly("VIEW_TRACE", "VIEW_DIAGNOSTICS");
        verifyNoInteractions(traceService, diffService);
    }
}
