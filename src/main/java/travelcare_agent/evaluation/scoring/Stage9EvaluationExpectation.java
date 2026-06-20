package travelcare_agent.evaluation.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

record Stage9EvaluationExpectation(
        String expectedAnswerabilityStatus,
        String expectedAnswerabilityReasonCode,
        String expectedRequiredAction,
        Boolean expectCitationRequired,
        List<Long> expectedCitationChunkIds,
        List<Long> expectedCitationDocumentIds,
        Boolean expectNoExpiredCitation,
        Boolean expectedFallbackUsed,
        Boolean expectBusinessDecisionLocked,
        Boolean expectRagMayOverrideBusinessDecision
) {
    static Stage9EvaluationExpectation from(JsonNode root) {
        return new Stage9EvaluationExpectation(
                text(root, "expectedAnswerabilityStatus"),
                text(root, "expectedAnswerabilityReasonCode"),
                text(root, "expectedRequiredAction"),
                bool(root, "expectCitationRequired", "requireCitation"),
                longs(root, "expectedCitationChunkIds"),
                longs(root, "expectedCitationDocumentIds"),
                bool(root, "expectNoExpiredCitation", "forbidExpiredCitation"),
                bool(root, "expectedFallbackUsed"),
                bool(root, "expectBusinessDecisionLocked"),
                overrideExpectation(root)
        );
    }

    boolean hasAnswerabilityExpectation() {
        return expectedAnswerabilityStatus != null || expectedAnswerabilityReasonCode != null
                || expectedRequiredAction != null;
    }

    boolean hasCitationSourceExpectation() {
        return expectedCitationChunkIds != null || expectedCitationDocumentIds != null;
    }

    boolean hasFallbackExpectation() {
        return expectedFallbackUsed != null || "FALLBACK_REPLY".equals(expectedRequiredAction);
    }

    boolean hasBusinessGuardExpectation() {
        return expectBusinessDecisionLocked != null || expectRagMayOverrideBusinessDecision != null;
    }

    private static Boolean overrideExpectation(JsonNode root) {
        Boolean explicit = bool(root, "expectRagMayOverrideBusinessDecision");
        if (explicit != null) return explicit;
        Boolean forbidden = bool(root, "forbidRagBusinessOverride");
        return Boolean.TRUE.equals(forbidden) ? Boolean.FALSE : null;
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Boolean bool(JsonNode root, String... fields) {
        if (root == null) return null;
        for (String field : fields) {
            JsonNode value = root.get(field);
            if (value != null && !value.isNull()) return value.asBoolean();
        }
        return null;
    }

    private static List<Long> longs(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value == null || !value.isArray() ? null : Stage9ScoringSupport.longList(value);
    }
}
