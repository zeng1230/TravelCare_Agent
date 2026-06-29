package travelcare_agent.evaluation.dto;

import travelcare_agent.evaluation.entity.EvaluationBaseline;

import java.time.LocalDateTime;

public record EvaluationBaselineResponse(Long baselineId, Long datasetId, String datasetKey, Integer datasetVersion,
                                         Long runId, String promotedBy, LocalDateTime promotedAt,
                                         LocalDateTime createdAt) {
    public static EvaluationBaselineResponse from(EvaluationBaseline v) {
        return v == null ? null : new EvaluationBaselineResponse(v.getId(), v.getDatasetId(), v.getDatasetKey(), v.getDatasetVersion(), v.getRunId(), v.getPromotedBy(), v.getPromotedAt(), v.getCreatedAt());
    }
}
