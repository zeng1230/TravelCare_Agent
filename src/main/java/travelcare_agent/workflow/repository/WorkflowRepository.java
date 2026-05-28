package travelcare_agent.workflow.repository;

import travelcare_agent.workflow.entity.Workflow;

import java.util.List;
import java.util.Optional;

public interface WorkflowRepository {

    Workflow save(Workflow workflow);

    Optional<Workflow> findById(Long workflowId);

    List<Workflow> findBySessionId(Long sessionId);
}
