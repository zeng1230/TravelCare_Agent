package travelcare_agent.evaluation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class BaselineComparisonRules {
    public BaselineComparisonDecision compare(EvaluationCaseResultFacts baseline, EvaluationCaseResultFacts current) {
        if ("PASSED".equals(baseline.caseStatus()) && "SKIPPED".equals(current.caseStatus()))
            return decision(RegressionCaseStatus.NOT_COMPARED, List.of("caseStatus"), "MEDIUM", "Current case was skipped", true);
        if (unknown(baseline.caseStatus(), current.caseStatus(), baseline.riskLevel(), current.riskLevel())
                || oneUnknown(baseline.policyDecision(), current.policyDecision())
                || oneUnknown(baseline.workflowStatus(), current.workflowStatus())
                || oneMissing(baseline.outputAssertionPassed(), current.outputAssertionPassed())
                || oneMissing(baseline.sideEffectSafetyPassed(), current.sideEffectSafetyPassed()))
            return decision(RegressionCaseStatus.NOT_COMPARED, List.of(), "MEDIUM", "Critical comparison facts are unavailable", true);
        List<String> changes = new ArrayList<>();
        boolean regression = false, improved = false;
        String risk = "NONE";
        if ("PASSED".equals(baseline.caseStatus()) && (current.caseStatus().equals("FAILED") || current.caseStatus().equals("ERROR"))) {
            changes.add("caseStatus");
            regression = true;
            risk = "HIGH";
        }
        if ((baseline.caseStatus().equals("FAILED") || baseline.caseStatus().equals("ERROR")) && current.caseStatus().equals("PASSED")) {
            changes.add("caseStatus");
            improved = true;
        }
        if (!baseline.policyDecision().equals(current.policyDecision())) {
            changes.add("policyDecision");
            regression = true;
            risk = "HIGH";
        }
        if (!baseline.workflowStatus().equals(current.workflowStatus())) {
            changes.add("workflowStatus");
            regression = true;
            risk = "HIGH";
        }
        if (Boolean.TRUE.equals(baseline.outputAssertionPassed()) && Boolean.FALSE.equals(current.outputAssertionPassed())) {
            changes.add("outputAssertionPassed");
            regression = true;
            risk = max(risk, "MEDIUM");
        }
        if (Boolean.FALSE.equals(baseline.outputAssertionPassed()) && Boolean.TRUE.equals(current.outputAssertionPassed())) {
            changes.add("outputAssertionPassed");
            improved = true;
        }
        if (Boolean.TRUE.equals(baseline.sideEffectSafetyPassed()) && Boolean.FALSE.equals(current.sideEffectSafetyPassed())) {
            changes.add("sideEffectSafetyPassed");
            regression = true;
            risk = "CRITICAL";
        }
        if (Boolean.FALSE.equals(baseline.sideEffectSafetyPassed()) && Boolean.TRUE.equals(current.sideEffectSafetyPassed())) {
            changes.add("sideEffectSafetyPassed");
            improved = true;
        }
        int riskCompare = rank(current.riskLevel()) - rank(baseline.riskLevel());
        if (riskCompare > 0) {
            changes.add("riskLevel");
            regression = true;
            risk = max(risk, current.riskLevel());
        } else if (riskCompare < 0) {
            changes.add("riskLevel");
            improved = true;
        }
        if (regression)
            return decision(RegressionCaseStatus.REGRESSION, changes, risk, "Regression detected: " + String.join(", ", changes), false);
        if (improved)
            return decision(RegressionCaseStatus.IMPROVED, changes, current.riskLevel(), "Improvement detected: " + String.join(", ", changes), false);
        return decision(RegressionCaseStatus.UNCHANGED, List.of(), current.riskLevel(), "No material change", false);
    }

    private BaselineComparisonDecision decision(RegressionCaseStatus s, List<String> c, String r, String m, boolean p) {
        return new BaselineComparisonDecision(s, c, r, m, p);
    }

    private boolean unknown(String... values) {
        for (String v : values) if ("UNKNOWN".equals(v)) return true;
        return false;
    }

    private boolean oneUnknown(String a, String b) {
        return "UNKNOWN".equals(a) ^ "UNKNOWN".equals(b);
    }

    private boolean oneMissing(Boolean a, Boolean b) {
        return (a == null) ^ (b == null);
    }

    private int rank(String v) {
        return switch (v) {
            case "LOW" -> 1;
            case "MEDIUM" -> 2;
            case "HIGH" -> 3;
            case "CRITICAL" -> 4;
            default -> 0;
        };
    }

    private String max(String a, String b) {
        return rank(a) >= rank(b) ? a : b;
    }
}
