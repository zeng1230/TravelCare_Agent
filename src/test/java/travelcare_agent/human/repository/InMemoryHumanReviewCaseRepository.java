package travelcare_agent.human.repository;

import travelcare_agent.enums.HumanReviewCaseStatus;
import travelcare_agent.human.entity.HumanReviewCase;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class InMemoryHumanReviewCaseRepository implements HumanReviewCaseRepository {

    private final AtomicLong ids = new AtomicLong(4000);
    private final Map<Long, HumanReviewCase> cases = new ConcurrentHashMap<>();

    @Override
    public HumanReviewCase save(HumanReviewCase hrCase) {
        if (hrCase.getId() == null) {
            hrCase.setId(ids.incrementAndGet());
            if (hrCase.getCreatedAt() == null) {
                hrCase.setCreatedAt(LocalDateTime.now());
            }
        }
        hrCase.setUpdatedAt(LocalDateTime.now());
        cases.put(hrCase.getId(), hrCase);
        return hrCase;
    }

    @Override
    public Optional<HumanReviewCase> findByIdAndTenantId(Long id, String tenantId) {
        return Optional.ofNullable(cases.get(id))
                .filter(c -> tenantId.equals(c.getTenantId()));
    }

    @Override
    public List<HumanReviewCase> findByTenantIdAndStatus(String tenantId, HumanReviewCaseStatus status) {
        return cases.values().stream()
                .filter(c -> tenantId.equals(c.getTenantId()))
                .filter(c -> c.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<HumanReviewCase> findByWorkflowIdAndTenantId(Long workflowId, String tenantId) {
        return cases.values().stream()
                .filter(c -> workflowId.equals(c.getWorkflowId()))
                .filter(c -> tenantId.equals(c.getTenantId()))
                .findFirst();
    }
}
