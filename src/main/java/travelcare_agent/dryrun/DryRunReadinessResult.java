package travelcare_agent.dryrun;

import java.util.List;

public record DryRunReadinessResult(
        boolean ready,
        String code,
        List<String> missingSnapshots,
        List<String> allowedActions
) {
    public static DryRunReadinessResult executable() {
        return new DryRunReadinessResult(true, "SUCCESS", List.of(),
                List.of("VIEW_TRACE", "VIEW_DIAGNOSTICS", "RUN_DRY_RUN"));
    }

    public static DryRunReadinessResult rejected(List<String> missing) {
        return new DryRunReadinessResult(false, "DRY_RUN_NOT_READY", List.copyOf(missing),
                List.of("VIEW_TRACE", "VIEW_DIAGNOSTICS"));
    }
}
