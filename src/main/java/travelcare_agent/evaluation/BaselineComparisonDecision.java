package travelcare_agent.evaluation;
import java.util.List;
public record BaselineComparisonDecision(RegressionCaseStatus status, List<String> changedFields,
        String highestRisk, String summary, boolean partial) {}
