package travelcare_agent.workflow.repository;

import travelcare_agent.workflow.entity.Workflow;

import java.util.List;
import java.util.Optional;

public interface WorkflowRepository {

    Workflow insert(Workflow workflow);

    int transitionIfCurrent(Workflow workflow, long expectedVersion, List<travelcare_agent.enums.WorkflowStatus> expectedStatuses);

    Optional<Workflow> findById(Long workflowId);

    List<Workflow> findBySessionId(Long sessionId);
}
