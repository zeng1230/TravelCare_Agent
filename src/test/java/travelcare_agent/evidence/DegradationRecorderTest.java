package travelcare_agent.evidence;

import org.junit.jupiter.api.Test;
import travelcare_agent.audit.AuditService;
import travelcare_agent.trace.TraceEventType;
import travelcare_agent.trace.TraceService;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DegradationRecorderTest {
    @Test
    void recordsOnlyStableAuditEvidenceAndTraceEvent() {
        AuditService audit = mock(AuditService.class);
        TraceService trace = mock(TraceService.class);
        DegradationRecorder recorder = new DegradationRecorder(audit, trace);
        CompletenessAssessment assessment = new CompletenessAssessment(CompletenessStatus.PARTIAL,
                List.of("TRACE", "CITATION"),
                List.of("TRACE_EVIDENCE_UNAVAILABLE", "CITATION_EVIDENCE_UNAVAILABLE"));

        recorder.record("HANDOFF_PACKET_PARTIAL_BUILD", 10L, 20L, "HANDOFF_PACKET", 30L,
                assessment, "trace-1");

        verify(audit).recordEvidenceDegradation(eq(10L), eq(20L), eq("HANDOFF_PACKET_PARTIAL_BUILD"),
                eq("HANDOFF_PACKET"), eq(30L), argThat(json -> json.contains("completenessStatus")
                        && json.contains("TRACE") && !json.contains("exception")));
        verify(trace).recordEvent(eq("trace-1"), isNull(), eq(TraceEventType.PARTIAL_BUILD_DEGRADED),
                eq("partial-build-degraded"), anyMap());
    }

    @Test
    void auditFailureDoesNotPreventTraceOrEscape() {
        AuditService audit = mock(AuditService.class);
        doThrow(new IllegalStateException("raw secret payload")).when(audit)
                .recordEvidenceDegradation(anyLong(), anyLong(), anyString(), anyString(), any(), anyString());
        TraceService trace = mock(TraceService.class);
        DegradationRecorder recorder = new DegradationRecorder(audit, trace);

        recorder.record("AGENTOPS_PARTIAL_EVIDENCE", 10L, null, "AGENTOPS_DEBUG", null,
                new CompletenessAssessment(CompletenessStatus.PARTIAL, List.of("TRACE"),
                        List.of("TRACE_EVIDENCE_UNAVAILABLE")), null);

        verifyNoInteractions(trace);
    }
}
