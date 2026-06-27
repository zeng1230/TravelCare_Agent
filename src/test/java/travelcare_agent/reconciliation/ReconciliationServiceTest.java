package travelcare_agent.reconciliation;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationServiceTest {

    @Test
    void createOrReusePendingKeepsOneJobPerSource() {
        InMemoryReconciliationJobRepository repository = new InMemoryReconciliationJobRepository();
        ReconciliationService service = new ReconciliationService(repository);

        ReconciliationJob first = service.createOrReusePending(
                "tool_call", 501L, "SIDE_EFFECT_TIMEOUT", "trace-1");
        ReconciliationJob duplicate = service.createOrReusePending(
                "tool_call", 501L, "SIDE_EFFECT_TIMEOUT", "trace-1");

        assertThat(duplicate.getId()).isEqualTo(first.getId());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(first.getStatus()).isEqualTo(ReconciliationJobStatus.PENDING);
    }

    @Test
    void mockReconciliationCanResolvePendingJobToFailed() {
        InMemoryReconciliationJobRepository repository = new InMemoryReconciliationJobRepository();
        ReconciliationService service = new ReconciliationService(repository);
        ReconciliationJob job = service.createOrReusePending(
                "tool_call", 501L, "SIDE_EFFECT_TIMEOUT", "trace-1");

        service.resolve(job.getId(), ReconciliationJobStatus.CONFIRMED_FAILED, "SUPPLIER_NOT_FOUND");

        ReconciliationJob saved = repository.findById(job.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ReconciliationJobStatus.CONFIRMED_FAILED);
        assertThat(saved.getResultCode()).isEqualTo("SUPPLIER_NOT_FOUND");
    }

    private static final class InMemoryReconciliationJobRepository implements ReconciliationJobRepository {
        private final AtomicLong ids = new AtomicLong(900);
        private final Map<Long, ReconciliationJob> byId = new ConcurrentHashMap<>();
        private final Map<String, Long> bySource = new ConcurrentHashMap<>();

        @Override
        public ReconciliationJob save(ReconciliationJob job) {
            if (job.getId() == null) {
                job.setId(ids.incrementAndGet());
                job.setCreatedAt(LocalDateTime.parse("2026-06-27T10:00:00"));
            }
            job.setUpdatedAt(LocalDateTime.parse("2026-06-27T10:00:00"));
            byId.put(job.getId(), job);
            bySource.put(job.getSourceType() + ":" + job.getSourceId(), job.getId());
            return job;
        }

        @Override
        public Optional<ReconciliationJob> findById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<ReconciliationJob> findBySource(String sourceType, Long sourceId) {
            Long id = bySource.get(sourceType + ":" + sourceId);
            return id == null ? Optional.empty() : findById(id);
        }

        int count() {
            return byId.size();
        }
    }
}
