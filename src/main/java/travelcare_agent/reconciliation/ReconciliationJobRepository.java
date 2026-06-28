package travelcare_agent.reconciliation;

import java.util.Optional;

public interface ReconciliationJobRepository {
    ReconciliationJob save(ReconciliationJob job);
    Optional<ReconciliationJob> findById(Long id);
    Optional<ReconciliationJob> findBySource(String sourceType, Long sourceId);
    default long countPending() { return 0; }
}
