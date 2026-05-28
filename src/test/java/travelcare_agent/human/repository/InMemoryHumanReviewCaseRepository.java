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
    public Optional<HumanReviewCase> findById(Long id) {
        return Optional.ofNullable(cases.get(id));
    }

    @Override
    public List<HumanReviewCase> findByStatus(HumanReviewCaseStatus status) {
        return cases.values().stream()
                .filter(c -> c.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<HumanReviewCase> findByWorkflowId(Long workflowId) {
        return cases.values().stream()
                .filter(c -> workflowId.equals(c.getWorkflowId()))
                .findFirst();
    }
}
