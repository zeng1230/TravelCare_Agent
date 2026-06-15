package travelcare_agent.evaluation.dto;
import travelcare_agent.evaluation.entity.EvaluationBaseline;
import java.time.LocalDateTime;
public record EvaluationBaselineResponse(Long datasetId,String datasetKey,Integer datasetVersion,Long baselineRunId,String promotedBy,LocalDateTime promotedAt){public static EvaluationBaselineResponse from(EvaluationBaseline v){return v==null?null:new EvaluationBaselineResponse(v.getDatasetId(),v.getDatasetKey(),v.getDatasetVersion(),v.getRunId(),v.getPromotedBy(),v.getPromotedAt());}}
