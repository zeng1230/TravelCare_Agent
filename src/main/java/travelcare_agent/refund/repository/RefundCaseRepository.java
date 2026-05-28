package travelcare_agent.refund.repository;

import travelcare_agent.refund.entity.RefundCase;

import java.util.Optional;

public interface RefundCaseRepository {

    RefundCase save(RefundCase refundCase);
    Optional<RefundCase> findById(Long id);
    Optional<RefundCase> findByWorkflowId(Long workflowId);
}

