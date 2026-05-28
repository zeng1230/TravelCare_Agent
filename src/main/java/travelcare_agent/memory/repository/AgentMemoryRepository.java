package travelcare_agent.memory.repository;

import travelcare_agent.enums.MemoryType;
import travelcare_agent.memory.entity.AgentMemory;

import java.util.List;
import java.util.Optional;

public interface AgentMemoryRepository {

    AgentMemory save(AgentMemory memory);

    Optional<AgentMemory> findById(Long id);

    List<AgentMemory> findActiveMemories(Long userId, List<MemoryType> types, int limit);
}
