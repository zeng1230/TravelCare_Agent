package travelcare_agent.evaluation.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SupplierFailureClassificationScorer implements EvaluationScorer {
    public String name() {
        return "supplierFailureClassification";
    }

    public ScoreResult score(EvaluationScoringContext c) {
        JsonNode e = c.expectation();
        String expectedFailure = text(e, "expectedSupplierFailureCode");
        Boolean expectedParticipated = bool(e, "expectSupplierGatewayParticipated");
        if (expectedFailure == null && expectedParticipated == null) return ScoreResult.skipped(name());
        List<String> failures = new ArrayList<>();
        if (expectedFailure != null && c.supplierFailureCode == null) failures.add("supplierFailureCode missing");
        if (expectedFailure != null && c.supplierFailureCode != null && !expectedFailure.equals(c.supplierFailureCode))
            failures.add("supplierFailureCode mismatch");
        if (expectedParticipated != null && c.supplierGatewayParticipated == null)
            failures.add("supplierGatewayParticipated missing");
        if (expectedParticipated != null && c.supplierGatewayParticipated != null
                && !expectedParticipated.equals(c.supplierGatewayParticipated))
            failures.add("supplierGatewayParticipated mismatch");
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("supplierFailureCode", expectedFailure);
        expected.put("supplierGatewayParticipated", expectedParticipated);
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("supplierFailureCode", c.supplierFailureCode);
        actual.put("supplierGatewayParticipated", c.supplierGatewayParticipated);
        return ScoreResult.of(name(), failures.isEmpty(), expected, actual,
                failures.isEmpty() ? "supplier failure classification matched"
                        : "supplier failure classification mismatch: " + failures);
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Boolean bool(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }
}
