package travelcare_agent.dryrun;

public record DryRunRequest(String reason, String providerMode, boolean compareAfterRun) {
}
