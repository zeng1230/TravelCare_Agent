package travelcare_agent.evaluation.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.*;

final class Stage9ScoringSupport {
    private Stage9ScoringSupport() {}

    static boolean hasStage9Snapshots(EvaluationScoringContext c) {
        return c != null && c.answerabilityDecisionSnapshot() != null && c.citationSummarySnapshot() != null;
    }

    static JsonNode expectation(EvaluationScoringContext c, String field) {
        return c == null || c.expectation() == null ? null : c.expectation().get(field);
    }

    static Stage9EvaluationExpectation expectation(EvaluationScoringContext c) {
        return Stage9EvaluationExpectation.from(c == null ? null : c.expectation());
    }

    static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    static boolean bool(JsonNode n) {
        return n != null && n.asBoolean(false);
    }

    static List<Long> longList(JsonNode n) {
        if (n == null || !n.isArray()) return List.of();
        List<Long> values = new ArrayList<>();
        for (JsonNode v : n) values.add(v.asLong());
        return values;
    }

    static List<Long> chunkIds(JsonNode citations) {
        if (citations == null || !citations.isArray()) return List.of();
        List<Long> values = new ArrayList<>();
        for (JsonNode citation : citations) {
            JsonNode chunkId = citation.get("chunkId");
            if (chunkId != null && !chunkId.isNull()) values.add(chunkId.asLong());
        }
        return values;
    }

    static List<Long> documentIds(JsonNode citations) {
        if (citations == null || !citations.isArray()) return List.of();
        List<Long> values = new ArrayList<>();
        for (JsonNode citation : citations) {
            JsonNode documentId = citation.get("documentId");
            if (documentId != null && !documentId.isNull()) values.add(documentId.asLong());
        }
        return values;
    }

    static List<Long> evidenceChunkIds(EvaluationScoringContext c) {
        if (c == null || c.answerabilityDecisionSnapshot() == null) return List.of();
        return longList(c.answerabilityDecisionSnapshot().path("evidenceChunkIds"));
    }

    static boolean containsExpiredCitation(JsonNode citations, LocalDateTime now) {
        if (citations == null || !citations.isArray()) return false;
        for (JsonNode citation : citations) {
            JsonNode effectiveTo = citation.get("effectiveTo");
            if (effectiveTo == null || effectiveTo.isNull() || effectiveTo.asText().isBlank()) continue;
            try {
                if (LocalDateTime.parse(effectiveTo.asText()).isBefore(now)) return true;
            } catch (RuntimeException ignored) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Object> actual(EvaluationScoringContext c) {
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("answerabilityStatus", c.answerabilityStatus());
        actual.put("answerabilityReasonCode", c.answerabilityReasonCode());
        actual.put("requiredAction", c.requiredAction());
        actual.put("fallbackUsed", c.fallbackUsed());
        actual.put("businessDecisionLocked", c.businessDecisionLocked());
        actual.put("ragMayExplainBusinessDecision", c.ragMayExplainBusinessDecision());
        actual.put("ragMayOverrideBusinessDecision", c.ragMayOverrideBusinessDecision());
        actual.put("citationChunkIds", chunkIds(c.citations()));
        actual.put("citationDocumentIds", documentIds(c.citations()));
        actual.put("evidenceChunkIds", evidenceChunkIds(c));
        actual.put("rejectedCitationCandidates", c.rejectedCitationCandidates());
        return actual;
    }
}
