package travelcare_agent.evaluation.repository;

import travelcare_agent.evaluation.entity.EvaluationBaseline;
import java.util.List;
import java.util.Optional;

public interface EvaluationBaselineRepository {
    EvaluationBaseline save(EvaluationBaseline value);
    Optional<EvaluationBaseline> findCurrent(Long datasetId, Long runId);
    List<EvaluationBaseline> findByDatasetId(Long datasetId);
}
