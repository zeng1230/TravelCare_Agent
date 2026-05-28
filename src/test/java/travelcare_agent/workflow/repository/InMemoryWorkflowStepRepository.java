package travelcare_agent.workflow.repository;

import travelcare_agent.workflow.entity.WorkflowStep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryWorkflowStepRepository implements WorkflowStepRepository {

    private final AtomicLong ids = new AtomicLong(4000);
    private final Map<Long, List<WorkflowStep>> stepsByWorkflow = new ConcurrentHashMap<>();

    @Override
    public synchronized WorkflowStep save(WorkflowStep step) {
        if (step.getId() == null) {
            step.setId(ids.incrementAndGet());
            stepsByWorkflow.computeIfAbsent(step.getWorkflowId(), ignored -> new ArrayList<>()).add(step);
            return step;
        }

        List<WorkflowStep> steps = stepsByWorkflow.computeIfAbsent(step.getWorkflowId(), ignored -> new ArrayList<>());
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).getId().equals(step.getId())) {
                steps.set(index, step);
                return step;
            }
        }
        steps.add(step);
        return step;
    }

    @Override
    public List<WorkflowStep> findByWorkflowId(Long workflowId) {
        return stepsByWorkflow.getOrDefault(workflowId, List.of()).stream()
                .sorted(Comparator.comparing(WorkflowStep::getId))
                .toList();
    }
}
