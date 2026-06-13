package travelcare_agent.dryrun;

import java.util.List;
import java.util.Map;

public record TraceDiffResult(
        Long diffId,
        String originalTraceId,
        String dryRunTraceId,
        boolean changed,
        String riskLevel,
        List<ChangedField> changedFields,
        Map<String, Object> originalSummary,
        Map<String, Object> dryRunSummary,
        String explanation
) {
    public record ChangedField(String field, Object original, Object dryRun, String severity) {
    }
}
