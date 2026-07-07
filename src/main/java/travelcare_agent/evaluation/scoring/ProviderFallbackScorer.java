package travelcare_agent.evaluation.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import travelcare_agent.evaluation.EvaluationLeakageSanitizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProviderFallbackScorer implements EvaluationScorer {
    public String name() {
        return "providerFallback";
    }

    public ScoreResult score(EvaluationScoringContext c) {
        JsonNode e = c.expectation();
        Boolean expectedFallback = bool(e, "expectedProviderFallbackUsed");
        Boolean forbidLeakage = bool(e, "forbidRawPromptOrProviderOutput");
        if (expectedFallback == null && forbidLeakage == null) return ScoreResult.skipped(name());
        List<String> failures = new ArrayList<>();
        if (expectedFallback != null && c.providerFallbackUsed == null) failures.add("providerFallbackUsed missing");
        if (expectedFallback != null && c.providerFallbackUsed != null && !expectedFallback.equals(c.providerFallbackUsed))
            failures.add("providerFallbackUsed mismatch");
        boolean leakage = Boolean.TRUE.equals(forbidLeakage)
                && EvaluationLeakageSanitizer.containsSensitiveLeakage(c.leakageCheckText);
        if (leakage) failures.add("leakage detected in provider diagnostics");
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("providerFallbackUsed", expectedFallback);
        expected.put("forbidRawPromptOrProviderOutput", forbidLeakage);
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("providerFallbackUsed", c.providerFallbackUsed);
        actual.put("leakageDetected", leakage);
        return ScoreResult.of(name(), failures.isEmpty(), expected, actual,
                failures.isEmpty() ? "provider fallback expectation matched" : "provider fallback mismatch: " + failures);
    }

    private static Boolean bool(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }
}
