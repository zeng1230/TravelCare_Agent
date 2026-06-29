package travelcare_agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import travelcare_agent.evaluation.entity.EvaluationCaseResult;

@Component
public class EvaluationCaseResultFactsExtractor {
    private final ObjectMapper json;

    public EvaluationCaseResultFactsExtractor(ObjectMapper json) {
        this.json = json;
    }

    public EvaluationCaseResultFacts extract(EvaluationCaseResult result) {
        String policy = "UNKNOWN", workflow = "UNKNOWN";
        Boolean output = null, safety = null;
        try {
            JsonNode scores = json.readTree(result.getScoresJson() == null ? "[]" : result.getScoresJson());
            for (JsonNode score : scores) {
                if (!score.path("applied").asBoolean(false)) continue;
                switch (score.path("scorer").asText()) {
                    case "policyDecision" -> policy = text(score.path("actual"));
                    case "workflowOutcome" -> workflow = text(score.path("actual"));
                    case "outputAssertions" -> output = score.path("passed").asBoolean();
                    case "sideEffectSafety" -> safety = score.path("passed").asBoolean();
                    default -> {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new EvaluationCaseResultFacts(value(result.getStatus()), policy, workflow,
                value(result.getRiskLevel()), output, safety);
    }

    private String text(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? "UNKNOWN" : value(n.asText());
    }

    private String value(String v) {
        return v == null || v.isBlank() ? "UNKNOWN" : v;
    }
}
