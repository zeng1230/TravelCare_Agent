package travelcare_agent.dryrun;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import travelcare_agent.trace.TraceQueryService;
import travelcare_agent.trace.TraceSnapshotType;
import travelcare_agent.trace.entity.TraceRun;
import travelcare_agent.trace.entity.TraceSnapshot;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DryRunReadinessCheckerTest {

    @Test
    void rejectsLegacyTraceWithoutCreatingAnExecutablePlan() {
        TraceQueryService queryService = mock(TraceQueryService.class);
        TraceRun run = run("legacy-trace", false);
        when(queryService.get("legacy-trace")).thenReturn(new TraceQueryService.TraceDetail(
                run, List.of(), List.of(), List.of(snapshot(TraceSnapshotType.FINAL_OUTPUT, "{\"answer\":\"old\"}"))
        ));

        DryRunReadinessResult result = new DryRunReadinessChecker(queryService, new ObjectMapper()).check("legacy-trace", "mock");

        assertThat(result.ready()).isFalse();
        assertThat(result.code()).isEqualTo("DRY_RUN_NOT_READY");
        assertThat(result.missingSnapshots()).contains("USER_INPUT", "TOOL_RESULT", "POLICY_INPUT");
        assertThat(result.allowedActions()).containsExactly("VIEW_TRACE", "VIEW_DIAGNOSTICS");
    }

    @Test
    void acceptsOnlyStructuredCompleteSnapshotsAndMockProvider() {
        TraceQueryService queryService = mock(TraceQueryService.class);
        TraceRun run = run("ready-trace", false);
        List<TraceSnapshot> snapshots = java.util.Arrays.stream(TraceSnapshotType.values())
                .map(type -> snapshot(type, payload(type)))
                .toList();
        when(queryService.get("ready-trace")).thenReturn(new TraceQueryService.TraceDetail(run, List.of(), List.of(), snapshots));

        DryRunReadinessResult result = new DryRunReadinessChecker(queryService, new ObjectMapper()).check("ready-trace", "mock");

        assertThat(result.ready()).isTrue();
        assertThat(result.missingSnapshots()).isEmpty();
    }

    @Test
    void doesNotRequireStage9AnswerabilitySnapshotsForExistingTraceReadiness() {
        TraceQueryService queryService = mock(TraceQueryService.class);
        TraceRun run = run("stage8-ready-trace", false);
        List<TraceSnapshot> snapshots = java.util.Arrays.stream(TraceSnapshotType.values())
                .filter(type -> type != TraceSnapshotType.ANSWERABILITY_INPUT)
                .filter(type -> type != TraceSnapshotType.ANSWERABILITY_DECISION)
                .filter(type -> type != TraceSnapshotType.CITATION_SUMMARY)
                .map(type -> snapshot(type, payload(type)))
                .toList();
        when(queryService.get("stage8-ready-trace")).thenReturn(new TraceQueryService.TraceDetail(run, List.of(), List.of(), snapshots));

        DryRunReadinessResult result = new DryRunReadinessChecker(queryService, new ObjectMapper()).check("stage8-ready-trace", "mock");

        assertThat(result.ready()).isTrue();
        assertThat(result.missingSnapshots()).doesNotContain("ANSWERABILITY_INPUT", "ANSWERABILITY_DECISION", "CITATION_SUMMARY");
    }

    private static TraceRun run(String traceId, boolean dryRun) {
        TraceRun run = new TraceRun();
        run.setTraceId(traceId);
        run.setSessionId(1L);
        run.setDryRun(dryRun);
        return run;
    }

    private static TraceSnapshot snapshot(TraceSnapshotType type, String payload) {
        TraceSnapshot snapshot = new TraceSnapshot();
        snapshot.setSnapshotType(type.name());
        snapshot.setPayloadJson(payload);
        return snapshot;
    }

    private static String payload(TraceSnapshotType type) {
        return switch (type) {
            case USER_INPUT -> "{\"message\":\"refund ORD-1001\",\"userId\":1}";
            case POLICY_INPUT -> "{\"evaluatedAt\":\"2026-06-13T10:00:00\",\"currentUserId\":1,\"order\":{\"orderId\":1001,\"orderNo\":\"ORD-1001\",\"userId\":1,\"status\":\"PAID\",\"refundable\":true,\"paidAmount\":100,\"departureTime\":\"2026-06-18T10:00:00\"}}";
            case TOOL_RESULT -> "{\"toolName\":\"GetOrderTool\",\"status\":\"SUCCEEDED\",\"result\":{\"orderId\":1001,\"orderNo\":\"ORD-1001\",\"userId\":1,\"status\":\"PAID\",\"refundable\":true,\"paidAmount\":100,\"departureTime\":\"2026-06-18T10:00:00\"}}";
            default -> "{\"type\":\"" + type.name() + "\"}";
        };
    }
}
