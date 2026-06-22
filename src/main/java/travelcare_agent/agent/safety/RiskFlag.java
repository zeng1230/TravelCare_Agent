package travelcare_agent.agent.safety;

public record RiskFlag(String code, RiskSeverity severity, String reason) {
}
