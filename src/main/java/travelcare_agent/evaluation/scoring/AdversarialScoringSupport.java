package travelcare_agent.evaluation.scoring;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AdversarialScoringSupport {
    private AdversarialScoringSupport() {
    }

    static boolean enabled(EvaluationScoringContext context, String field) {
        JsonNode value = context == null || context.expectation() == null ? null : context.expectation().get(field);
        return value != null && value.asBoolean(false);
    }

    static String expectedText(EvaluationScoringContext context, String field) {
        JsonNode value = context == null || context.expectation() == null ? null : context.expectation().get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static List<String> expectedStrings(EvaluationScoringContext context, String field) {
        JsonNode value = context == null || context.expectation() == null ? null : context.expectation().get(field);
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray()) return List.of(value.asText());
        List<String> values = new ArrayList<>();
        value.forEach(item -> values.add(item.asText()));
        return values;
    }

    static boolean sideEffectSafe(EvaluationScoringContext context) {
        return context.sideEffectCheckResult() != null && context.sideEffectCheckResult().safe();
    }

    static Map<String, Object> actual(EvaluationScoringContext context) {
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("safetyDecision", context.safetyDecision);
        actual.put("safetyReasonCode", context.safetyReasonCode);
        actual.put("riskFlags", context.safetyRiskFlags);
        actual.put("policyDecision", context.policyDecision());
        actual.put("workflowStatus", context.workflowStatus());
        actual.put("businessDecisionLocked", context.businessDecisionLocked());
        actual.put("ragMayOverrideBusinessDecision", context.ragMayOverrideBusinessDecision());
        actual.put("sideEffectSafety", sideEffectSafe(context));
        return actual;
    }
}
