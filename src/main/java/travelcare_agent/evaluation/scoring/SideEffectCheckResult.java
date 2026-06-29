package travelcare_agent.evaluation.scoring;

import java.util.Map;

public record SideEffectCheckResult(boolean safe, Map<String, Long> before, Map<String, Long> after, String reason) {
}
