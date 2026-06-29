package travelcare_agent.evaluation.repository;

import travelcare_agent.evaluation.entity.EvaluationRun;

import java.util.Optional;

public interface EvaluationRunRepository {
    EvaluationRun save(EvaluationRun value);

    Optional<EvaluationRun> findById(Long id);
}
