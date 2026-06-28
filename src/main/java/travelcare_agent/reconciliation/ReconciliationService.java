package travelcare_agent.reconciliation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.observability.TravelCareMetrics;

@Service
public class ReconciliationService {
    private final ReconciliationJobRepository repository;
    private final TravelCareMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public ReconciliationService(ReconciliationJobRepository repository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) TravelCareMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
        if (metrics != null) metrics.gauge("travelcare.reconciliation.pending", repository::countPending);
    }

    public ReconciliationService(ReconciliationJobRepository repository) {
        this(repository, null);
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
            ReconciliationJob saved = repository.save(job);
            if (metrics != null) metrics.reconciliationCreated(saved.getSourceType(), saved.getReasonCode());
            return saved;
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
        ReconciliationJob saved = repository.save(job);
        if (metrics != null) metrics.reconciliationResolved(saved.getSourceType(), saved.getStatus().name(), resultCode);
        return saved;
    }
}
