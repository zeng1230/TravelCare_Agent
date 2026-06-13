package travelcare_agent.dryrun;

import java.util.List;

public record DryRunResult(
        String code,
        String status,
        String originalTraceId,
        String dryRunTraceId,
        Long diffId,
        Boolean changed,
        String riskLevel,
        String message,
        List<String> missingSnapshots,
        List<String> allowedActions
) {
    public static DryRunResult rejected(String originalTraceId, DryRunReadinessResult readiness) {
        return new DryRunResult(readiness.code(), "REJECTED", originalTraceId, null, null, null, null,
                "dry run is not ready", readiness.missingSnapshots(), readiness.allowedActions());
    }

    public static DryRunResult succeeded(String originalTraceId, String dryRunTraceId, TraceDiffResult diff) {
        return new DryRunResult("SUCCESS", "SUCCEEDED", originalTraceId, dryRunTraceId,
                diff == null ? null : diff.diffId(), diff == null ? null : diff.changed(),
                diff == null ? null : diff.riskLevel(), "dry run completed", List.of(),
                diff == null ? List.of("VIEW_TRACE", "VIEW_DIAGNOSTICS") : List.of("VIEW_TRACE", "VIEW_DIAGNOSTICS", "VIEW_DIFF"));
    }

    public static DryRunResult failed(String originalTraceId, String dryRunTraceId, String code) {
        return new DryRunResult(code, "FAILED", originalTraceId, dryRunTraceId, null, true, "HIGH",
                "dry run failed", List.of(), List.of("VIEW_TRACE", "VIEW_DIAGNOSTICS"));
    }
}
