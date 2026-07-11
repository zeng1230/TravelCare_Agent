package travelcare_agent.evidence;

public record BuildOutcome<T>(T value, CompletenessAssessment completeness, String availableTraceId) {
}
