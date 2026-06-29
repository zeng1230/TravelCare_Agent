package travelcare_agent.evaluation.repository;

import travelcare_agent.evaluation.entity.EvaluationDataset;

import java.util.Optional;

public interface EvaluationDatasetRepository {
    EvaluationDataset save(EvaluationDataset value);

    Optional<EvaluationDataset> findById(Long id);

    Optional<EvaluationDataset> findByKeyAndVersion(String key, int version);

    int findMaxVersion(String key);
}
