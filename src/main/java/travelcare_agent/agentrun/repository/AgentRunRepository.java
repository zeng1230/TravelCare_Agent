package travelcare_agent.agentrun.repository;

import travelcare_agent.agentrun.entity.AgentRun;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository {

    AgentRun save(AgentRun agentRun);

    Optional<AgentRun> findById(Long id);

    List<AgentRun> findBySessionId(Long sessionId, long pageNo, long pageSize);

    long countBySessionId(Long sessionId);

    List<AgentRun> findAll();
}
