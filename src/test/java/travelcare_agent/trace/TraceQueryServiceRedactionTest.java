package travelcare_agent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import travelcare_agent.trace.entity.TraceEvent;
import travelcare_agent.trace.entity.TraceRun;
import travelcare_agent.trace.entity.TraceSnapshot;
import travelcare_agent.trace.entity.TraceSpan;
import travelcare_agent.trace.repository.TraceEventRepository;
import travelcare_agent.trace.repository.TraceRunRepository;
import travelcare_agent.trace.repository.TraceSnapshotRepository;
import travelcare_agent.trace.repository.TraceSpanRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceQueryServiceRedactionTest {

    @Test
    void getReturnsSanitizedCopiesWithoutMutatingRepositoryEntities() {
        TraceRun run = run();
        run.setErrorMessage("Authorization: Bearer run-token");
        run.setMetadataJson("{\"rawPrompt\":\"prompt secret\",\"email\":\"user@example.com\"}");
        TraceSpan span = span();
        span.setErrorMessage("raw_provider_output={bad json} api_key=sk-private");
        span.setMetadataJson("{\"phone\":\"13812345678\"}");
        TraceEvent event = event();
        event.setMetadataJson("{\"authorization\":\"Bearer event-token\"}");
        TraceSnapshot snapshot = snapshot(TraceSnapshotType.RETRIEVAL_SUMMARY.name(), """
                {"results":[{"title":"raw prompt: hidden",
                "sourceUri":"https://example.com/sop?token=abc&ok=1#secret"}],
                "rawProviderOutput":"Bearer snapshot-token"}
                """);

        TraceQueryService service = service(run, List.of(span), List.of(event), List.of(snapshot));

        TraceQueryService.TraceDetail detail = service.get("trace-1");

        assertThat(detail.run()).isNotSameAs(run);
        assertThat(detail.spans()).singleElement().isNotSameAs(span);
        assertThat(detail.events()).singleElement().isNotSameAs(event);
        assertThat(detail.snapshots()).singleElement().isNotSameAs(snapshot);
        assertThat(detail.run().getErrorMessage()).doesNotContain("run-token");
        assertThat(detail.run().getMetadataJson()).doesNotContain("prompt secret", "user@example.com");
        assertThat(detail.spans().get(0).getErrorMessage()).doesNotContain("raw_provider_output", "sk-private");
        assertThat(detail.spans().get(0).getMetadataJson()).doesNotContain("13812345678");
        assertThat(detail.events().get(0).getMetadataJson()).doesNotContain("event-token");
        assertThat(detail.snapshots().get(0).getPayloadJson())
                .doesNotContain("raw prompt", "token=abc", "#secret", "snapshot-token")
                .contains("https://example.com/sop?ok=1");

        assertThat(run.getErrorMessage()).contains("run-token");
        assertThat(span.getMetadataJson()).contains("13812345678");
        assertThat(snapshot.getPayloadJson()).contains("token=abc", "#secret", "snapshot-token");
    }

    @Test
    void diagnosticsUsesSanitizedDetailAndCitationUris() {
        TraceRun run = run();
        TraceSnapshot citation = snapshot(TraceSnapshotType.CITATION_SUMMARY.name(), """
                {"citations":[{"retrievalRunId":"ret-1","documentId":7,"chunkId":8,
                "title":"Refund SOP","sourceUri":"https://example.com/sop?secret=abc&ok=1#hidden"}]}
                """);

        TraceQueryService.TraceDiagnostics diagnostics = service(run, List.of(), List.of(), List.of(citation))
                .diagnostics("trace-1");

        assertThat(diagnostics.citations()).singleElement()
                .satisfies(value -> assertThat(value.sourceUri()).isEqualTo("https://example.com/sop?ok=1"));
    }

    private static TraceQueryService service(TraceRun run, List<TraceSpan> spans, List<TraceEvent> events,
            List<TraceSnapshot> snapshots) {
        TraceRunRepository runs = mock(TraceRunRepository.class);
        when(runs.findByTraceId("trace-1")).thenReturn(Optional.of(run));
        TraceSpanRepository spanRepository = mock(TraceSpanRepository.class);
        when(spanRepository.findByTraceId("trace-1")).thenReturn(spans);
        TraceEventRepository eventRepository = mock(TraceEventRepository.class);
        when(eventRepository.findByTraceId("trace-1")).thenReturn(events);
        TraceSnapshotRepository snapshotRepository = mock(TraceSnapshotRepository.class);
        when(snapshotRepository.findByTraceId("trace-1")).thenReturn(snapshots);
        return new TraceQueryService(runs, spanRepository, eventRepository, snapshotRepository,
                new ObjectMapper(), new RedactionService());
    }

    private static TraceRun run() {
        TraceRun run = new TraceRun();
        run.setTraceId("trace-1");
        run.setStatus("SUCCEEDED");
        run.setProvider("mock");
        run.setModel("mock-stage10a");
        run.setPromptVersion("stage10a-default");
        return run;
    }

    private static TraceSpan span() {
        TraceSpan span = new TraceSpan();
        span.setTraceId("trace-1");
        span.setSpanId("span-1");
        span.setSpanType("MODEL");
        span.setStatus("FAILED");
        return span;
    }

    private static TraceEvent event() {
        TraceEvent event = new TraceEvent();
        event.setTraceId("trace-1");
        event.setEventType("FAILED");
        event.setName("model-failed");
        return event;
    }

    private static TraceSnapshot snapshot(String type, String payload) {
        TraceSnapshot snapshot = new TraceSnapshot();
        snapshot.setTraceId("trace-1");
        snapshot.setSnapshotType(type);
        snapshot.setPayloadJson(payload);
        snapshot.setRedactionSummaryJson("{\"redactedCount\":0}");
        return snapshot;
    }
}
