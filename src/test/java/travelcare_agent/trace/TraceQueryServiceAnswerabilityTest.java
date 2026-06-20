package travelcare_agent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import travelcare_agent.trace.entity.TraceRun;
import travelcare_agent.trace.entity.TraceSnapshot;
import travelcare_agent.trace.repository.TraceEventRepository;
import travelcare_agent.trace.repository.TraceRunRepository;
import travelcare_agent.trace.repository.TraceSnapshotRepository;
import travelcare_agent.trace.repository.TraceSpanRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceQueryServiceAnswerabilityTest {

    @Test
    void diagnosticsKeepsLegacyTracesCompatibleWhenAnswerabilitySnapshotsAreMissing() {
        TraceQueryService service = service("legacy-trace", List.of());

        TraceQueryService.TraceDiagnostics diagnostics = service.diagnostics("legacy-trace");

        assertThat(diagnostics.answerability()).isNull();
        assertThat(diagnostics.citations()).isEmpty();
        assertThat(diagnostics.rejectedCitationCandidates()).isEmpty();
    }

    @Test
    void diagnosticsExposesAnswerabilityCitationsAndRejectedCandidatesFromSnapshots() {
        TraceSnapshot decision = snapshot(TraceSnapshotType.ANSWERABILITY_DECISION.name(), """
                {
                  "status": "ANSWERABLE",
                  "reasonCode": "SUFFICIENT_CONTEXT",
                  "requiredAction": "ALLOW_MODEL",
                  "evidenceChunkIds": [1001],
                  "businessDecisionLocked": false,
                  "ragMayExplainBusinessDecision": false,
                  "ragMayOverrideBusinessDecision": false
                }
                """);
        TraceSnapshot summary = snapshot(TraceSnapshotType.CITATION_SUMMARY.name(), """
                {
                  "citations": [
                    {
                      "retrievalRunId": "run-1",
                      "chunkId": 1001,
                      "documentId": 2001,
                      "title": "Refund SOP",
                      "sourceUri": "https://example.com/refund"
                    }
                  ],
                  "rejectedCitationCandidates": [
                    {
                      "retrievalRunId": "run-1",
                      "chunkId": 1002,
                      "documentId": 2002,
                      "title": "Expired SOP",
                      "reasonCode": "EXPIRED_SOURCE"
                    }
                  ]
                }
                """);
        TraceQueryService service = service("trace-1", List.of(decision, summary));

        TraceQueryService.TraceDiagnostics diagnostics = service.diagnostics("trace-1");

        assertThat(diagnostics.answerability()).isNotNull();
        assertThat(diagnostics.answerability().status()).isEqualTo("ANSWERABLE");
        assertThat(diagnostics.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(1001L);
            assertThat(citation.retrievalRunId()).isEqualTo("run-1");
        });
        assertThat(diagnostics.rejectedCitationCandidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.chunkId()).isEqualTo(1002L);
            assertThat(candidate.reasonCode()).isEqualTo("EXPIRED_SOURCE");
        });
    }

    @Test
    void diagnosticsUsesLatestPostPolicyBusinessLockSnapshot() {
        TraceSnapshot beforePolicy = snapshot(TraceSnapshotType.ANSWERABILITY_DECISION.name(), """
                {"status":"ANSWERABLE","reasonCode":"SUFFICIENT_CONTEXT","requiredAction":"ALLOW_MODEL",
                 "evidenceChunkIds":[1001],"businessDecisionLocked":false,
                 "ragMayExplainBusinessDecision":false,"ragMayOverrideBusinessDecision":false}
                """);
        TraceSnapshot afterPolicy = snapshot(TraceSnapshotType.ANSWERABILITY_DECISION.name(), """
                {"status":"ANSWERABLE","reasonCode":"SUFFICIENT_CONTEXT","requiredAction":"ALLOW_MODEL",
                 "evidenceChunkIds":[1001],"businessDecisionLocked":true,
                 "ragMayExplainBusinessDecision":true,"ragMayOverrideBusinessDecision":false}
                """);
        TraceQueryService service = service("locked-trace", List.of(beforePolicy, afterPolicy));

        TraceQueryService.TraceDiagnostics diagnostics = service.diagnostics("locked-trace");

        assertThat(diagnostics.answerability().businessDecisionLocked()).isTrue();
        assertThat(diagnostics.answerability().ragMayExplainBusinessDecision()).isTrue();
        assertThat(diagnostics.answerability().ragMayOverrideBusinessDecision()).isFalse();
    }

    private static TraceQueryService service(String traceId, List<TraceSnapshot> snapshots) {
        TraceRunRepository runs = mock(TraceRunRepository.class);
        TraceSpanRepository spans = mock(TraceSpanRepository.class);
        TraceEventRepository events = mock(TraceEventRepository.class);
        TraceSnapshotRepository snapshotRepository = mock(TraceSnapshotRepository.class);
        TraceRun run = new TraceRun();
        run.setTraceId(traceId);
        run.setStatus("SUCCEEDED");
        run.setStartedAt(LocalDateTime.parse("2026-06-16T10:00:00"));
        run.setFinishedAt(LocalDateTime.parse("2026-06-16T10:00:01"));
        when(runs.findByTraceId(traceId)).thenReturn(Optional.of(run));
        when(spans.findByTraceId(traceId)).thenReturn(List.of());
        when(events.findByTraceId(traceId)).thenReturn(List.of());
        when(snapshotRepository.findByTraceId(traceId)).thenReturn(snapshots);
        return new TraceQueryService(runs, spans, events, snapshotRepository, new ObjectMapper());
    }

    private static TraceSnapshot snapshot(String type, String payload) {
        TraceSnapshot snapshot = new TraceSnapshot();
        snapshot.setSnapshotType(type);
        snapshot.setPayloadJson(payload);
        return snapshot;
    }
}
