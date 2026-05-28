package travelcare_agent.workflow.repository;

import travelcare_agent.workflow.entity.WorkflowStep;

import java.util.List;

public interface WorkflowStepRepository {

    WorkflowStep save(WorkflowStep step);

    List<WorkflowStep> findByWorkflowId(Long workflowId);
}
