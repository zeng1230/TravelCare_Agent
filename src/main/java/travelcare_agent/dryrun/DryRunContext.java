package travelcare_agent.dryrun;

public record DryRunContext(String originalTraceId, String dryRunTraceId, String reason, String providerMode) {
}
