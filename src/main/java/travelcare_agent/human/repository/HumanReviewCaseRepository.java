package travelcare_agent.human.repository;

import travelcare_agent.enums.HumanReviewCaseStatus;
import travelcare_agent.human.entity.HumanReviewCase;

import java.util.List;
import java.util.Optional;

public interface HumanReviewCaseRepository {
    HumanReviewCase save(HumanReviewCase hrCase);
    Optional<HumanReviewCase> findById(Long id);
    List<HumanReviewCase> findByStatus(HumanReviewCaseStatus status);
    Optional<HumanReviewCase> findByWorkflowId(Long workflowId);
}
