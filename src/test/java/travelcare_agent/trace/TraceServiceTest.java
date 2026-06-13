package travelcare_agent.trace;

import org.junit.jupiter.api.Test;
import travelcare_agent.trace.entity.TraceRun;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class TraceServiceTest {

    @Test
    void rootCreationFailureReturnsUnavailableWithoutInventingQueryableTraceId() {
        TracePersistenceService persistence = mock(TracePersistenceService.class);
        when(persistence.saveRun(any())).thenThrow(new IllegalStateException("database unavailable"));
        TraceService service = new TraceService(persistence, new RedactionService());

        TraceService.RootTrace root = service.startRootRun(
                10L, 20L, null, "mock", "mock-model", "prompt-v1", Map.of("email", "user@example.com")
        );

        assertThat(root.available()).isFalse();
        assertThat(root.traceId()).isNull();
        assertThat(root.rootSpanId()).isNull();
    }

    @Test
    void redactsRunMetadataBeforePersistence() {
        TracePersistenceService persistence = mock(TracePersistenceService.class);
        when(persistence.saveRun(any())).thenAnswer(invocation -> invocation.getArgument(0, TraceRun.class));
        when(persistence.saveSpan(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TraceService service = new TraceService(persistence, new RedactionService());

        TraceService.RootTrace root = service.startRootRun(
                10L, 20L, null, "mock", "mock-model", "prompt-v1",
                Map.of("authorization", "Bearer highly-secret")
        );

        assertThat(root.available()).isTrue();
        org.mockito.ArgumentCaptor<TraceRun> captor = org.mockito.ArgumentCaptor.forClass(TraceRun.class);
        org.mockito.Mockito.verify(persistence).saveRun(captor.capture());
        assertThat(captor.getValue().getMetadataJson()).doesNotContain("highly-secret");
    }

    @Test
    void redactsEventSnapshotAndErrorBeforePersistence() {
        TracePersistenceService persistence = mock(TracePersistenceService.class);
        TraceService service = new TraceService(persistence, new RedactionService());
        when(persistence.saveEvent(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.saveSnapshot(any())).thenAnswer(invocation -> invocation.getArgument(0));
        travelcare_agent.trace.entity.TraceSpan span = new travelcare_agent.trace.entity.TraceSpan();
        span.setSpanId("span-1"); span.setTraceId("trace-1"); span.setStatus("RUNNING"); span.setStartedAt(java.time.LocalDateTime.now());
        when(persistence.findSpan("span-1")).thenReturn(span);
        when(persistence.saveSpan(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordEvent("trace-1", "span-1", TraceEventType.FAILED, "failure",
                Map.of("cookie", "session=secret"));
        service.recordSnapshot("trace-1", "span-1", "INPUT", "REQUEST", "1",
                Map.of("phone", "13812345678", "note", "mail user@example.com"));
        service.finishSpanFailure(new TraceService.SpanHandle("trace-1", "span-1", true),
                "FAILED", new IllegalStateException("Bearer raw-secret"), Map.of("apiKey", "raw-key"));

        org.mockito.ArgumentCaptor<travelcare_agent.trace.entity.TraceEvent> eventCaptor = org.mockito.ArgumentCaptor.forClass(travelcare_agent.trace.entity.TraceEvent.class);
        verify(persistence, org.mockito.Mockito.atLeastOnce()).saveEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).allSatisfy(e -> assertThat(e.getMetadataJson()).doesNotContain("session=secret", "raw-key"));
        org.mockito.ArgumentCaptor<travelcare_agent.trace.entity.TraceSnapshot> snapshotCaptor = org.mockito.ArgumentCaptor.forClass(travelcare_agent.trace.entity.TraceSnapshot.class);
        verify(persistence).saveSnapshot(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().getPayloadJson()).doesNotContain("13812345678", "user@example.com");
        org.mockito.ArgumentCaptor<travelcare_agent.trace.entity.TraceSpan> spanCaptor = org.mockito.ArgumentCaptor.forClass(travelcare_agent.trace.entity.TraceSpan.class);
        verify(persistence).saveSpan(spanCaptor.capture());
        assertThat(spanCaptor.getValue().getErrorMessage()).doesNotContain("raw-secret");
        assertThat(spanCaptor.getValue().getMetadataJson()).doesNotContain("raw-key");
    }

    @Test
    void createsPersistedDryRunRootOnlyWhenExplicitlyRequested() {
        TracePersistenceService persistence = mock(TracePersistenceService.class);
        when(persistence.saveRun(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.saveSpan(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TraceService service = new TraceService(persistence, new RedactionService());

        TraceService.RootTrace root = service.startDryRunRoot(
                10L, 20L, "original-trace", "manual-debug", "mock", Map.of("schemaVersion", "7B")
        );

        assertThat(root.available()).isTrue();
        org.mockito.ArgumentCaptor<TraceRun> captor = org.mockito.ArgumentCaptor.forClass(TraceRun.class);
        verify(persistence).saveRun(captor.capture());
        assertThat(captor.getValue().getDryRun()).isTrue();
        assertThat(captor.getValue().getMetadataJson()).contains("original-trace", "manual-debug");
    }
}
