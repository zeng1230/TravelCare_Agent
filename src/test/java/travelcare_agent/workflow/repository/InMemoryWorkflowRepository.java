package travelcare_agent.workflow.repository;

import travelcare_agent.workflow.entity.Workflow;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryWorkflowRepository implements WorkflowRepository {

    private final AtomicLong ids = new AtomicLong(3000);
    private final Map<Long, Workflow> workflows = new ConcurrentHashMap<>();

    @Override
    public Workflow insert(Workflow workflow) {
        if (workflow.getId() == null) {
            workflow.setId(ids.incrementAndGet());
        }
        workflows.put(workflow.getId(), workflow);
        return workflow;
    }

    @Override
    public synchronized int transitionIfCurrent(Workflow workflow, long expectedVersion,
                                                List<travelcare_agent.enums.WorkflowStatus> expectedStatuses) {
        Workflow current = workflows.get(workflow.getId());
        if (current == null || current.getVersion() == null || current.getVersion() != expectedVersion) return 0;
        workflow.setVersion(expectedVersion + 1);
        workflows.put(workflow.getId(), workflow);
        return 1;
    }

    @Override
    public Optional<Workflow> findById(Long workflowId) {
        return Optional.ofNullable(workflows.get(workflowId));
    }

    @Override
    public List<Workflow> findBySessionId(Long sessionId) {
        return workflows.values().stream()
                .filter(w -> sessionId.equals(w.getSessionId()))
                .toList();
    }
}
