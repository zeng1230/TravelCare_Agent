package travelcare_agent.human.repository;

import travelcare_agent.enums.HumanReviewCaseStatus;
import travelcare_agent.human.entity.HumanReviewCase;

import java.util.List;
import java.util.Optional;

public interface HumanReviewCaseRepository {
    HumanReviewCase insert(HumanReviewCase hrCase);

    int assignIfOpen(HumanReviewCase hrCase, long expectedVersion);

    int resolveIfCurrent(HumanReviewCase hrCase, long expectedVersion);

    Optional<HumanReviewCase> findByIdAndTenantId(Long id, String tenantId);

    List<HumanReviewCase> findByTenantIdAndStatus(String tenantId, HumanReviewCaseStatus status);

    Optional<HumanReviewCase> findByWorkflowIdAndTenantId(Long workflowId, String tenantId);
}
