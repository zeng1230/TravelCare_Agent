package travelcare_agent.memory.repository;

import travelcare_agent.enums.MemoryType;
import travelcare_agent.memory.entity.AgentMemory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryAgentMemoryRepository implements AgentMemoryRepository {

    private final AtomicLong ids = new AtomicLong(9000);
    private final List<AgentMemory> memories = new ArrayList<>();

    @Override
    public synchronized AgentMemory save(AgentMemory memory) {
        if (memory.getId() == null) {
            memory.setId(ids.incrementAndGet());
            memories.add(memory);
        } else {
            memories.removeIf(m -> m.getId().equals(memory.getId()));
            memories.add(memory);
        }
        return memory;
    }

    @Override
    public synchronized Optional<AgentMemory> findById(Long id) {
        return memories.stream()
                .filter(m -> m.getId().equals(id))
                .findFirst();
    }

    @Override
    public synchronized List<AgentMemory> findActiveMemories(Long userId, List<MemoryType> types, int limit) {
        LocalDateTime now = LocalDateTime.now();
        return memories.stream()
                .filter(m -> m.getUserId().equals(userId))
                .filter(m -> "ACTIVE".equals(m.getStatus()))
                .filter(m -> m.getExpiresAt() == null || m.getExpiresAt().isAfter(now))
                .filter(m -> types == null || types.isEmpty() || types.contains(m.getMemoryType()))
                .sorted((m1, m2) -> {
                    int c = m2.getUpdatedAt().compareTo(m1.getUpdatedAt());
                    if (c != 0) return c;
                    return m2.getId().compareTo(m1.getId());
                })
                .limit(limit)
                .toList();
    }

    public List<AgentMemory> findAll() {
        return List.copyOf(memories);
    }
}
