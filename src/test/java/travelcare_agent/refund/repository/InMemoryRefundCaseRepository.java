package travelcare_agent.refund.repository;

import travelcare_agent.refund.entity.RefundCase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import java.util.Optional;

public class InMemoryRefundCaseRepository implements RefundCaseRepository {

    private final AtomicLong ids = new AtomicLong(5000);
    private final List<RefundCase> cases = new ArrayList<>();

    @Override
    public synchronized RefundCase save(RefundCase refundCase) {
        if (refundCase.getId() == null) {
            refundCase.setId(ids.incrementAndGet());
        }
        cases.add(refundCase);
        return refundCase;
    }

    @Override
    public synchronized Optional<RefundCase> findById(Long id) {
        return cases.stream().filter(c -> id.equals(c.getId())).findFirst();
    }

    @Override
    public synchronized Optional<RefundCase> findByWorkflowId(Long workflowId) {
        return cases.stream().filter(c -> workflowId.equals(c.getWorkflowId())).findFirst();
    }

    public List<RefundCase> findAll() {
        return List.copyOf(cases);
    }
}

