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
    public Workflow save(Workflow workflow) {
        if (workflow.getId() == null) {
            workflow.setId(ids.incrementAndGet());
        }
        workflows.put(workflow.getId(), workflow);
        return workflow;
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
