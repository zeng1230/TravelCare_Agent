package travelcare_agent.evaluation.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record CreateEvaluationCaseRequest(String caseKey, String name, Long sourceTraceId, JsonNode expectationJson,
                                          JsonNode tagsJson, Boolean enabled) {
}
