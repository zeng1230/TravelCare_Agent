package travelcare_agent.evaluation;

public record EvaluationCaseResultFacts(String caseStatus, String policyDecision, String workflowStatus,
                                        String riskLevel, Boolean outputAssertionPassed,
                                        Boolean sideEffectSafetyPassed) {
}
