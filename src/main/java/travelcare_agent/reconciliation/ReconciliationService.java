package travelcare_agent.reconciliation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {
    private final ReconciliationJobRepository repository;

    public ReconciliationService(ReconciliationJobRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ReconciliationJob createOrReusePending(String sourceType, Long sourceId, String reasonCode, String traceId) {
        return repository.findBySource(sourceType, sourceId).orElseGet(() -> {
            ReconciliationJob job = new ReconciliationJob();
            job.setSourceType(sourceType);
            job.setSourceId(sourceId);
            job.setReasonCode(reasonCode);
            job.setTraceId(traceId);
            job.setStatus(ReconciliationJobStatus.PENDING);
            return repository.save(job);
        });
    }

    @Transactional
    public ReconciliationJob resolve(Long jobId, ReconciliationJobStatus status, String resultCode) {
        if (status == ReconciliationJobStatus.PENDING) {
            throw new IllegalArgumentException("resolution status must be terminal or UNKNOWN");
        }
        ReconciliationJob job = repository.findById(jobId).orElseThrow();
        job.setStatus(status);
        job.setResultCode(resultCode);
        return repository.save(job);
    }
}
