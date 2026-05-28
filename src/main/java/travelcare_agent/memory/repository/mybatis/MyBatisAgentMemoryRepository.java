package travelcare_agent.memory.repository.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;
import travelcare_agent.enums.MemoryType;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.memory.repository.AgentMemoryRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisAgentMemoryRepository implements AgentMemoryRepository {

    private final MyBatisAgentMemoryMapper mapper;

    public MyBatisAgentMemoryRepository(MyBatisAgentMemoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AgentMemory save(AgentMemory memory) {
        if (memory.getId() == null) {
            mapper.insert(memory);
        } else {
            mapper.updateById(memory);
        }
        return memory;
    }

    @Override
    public Optional<AgentMemory> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    @Override
    public List<AgentMemory> findActiveMemories(Long userId, List<MemoryType> types, int limit) {
        LambdaQueryWrapper<AgentMemory> wrapper = Wrappers.lambdaQuery(AgentMemory.class)
                .eq(AgentMemory::getUserId, userId)
                .eq(AgentMemory::getStatus, "ACTIVE")
                .and(w -> w.isNull(AgentMemory::getExpiresAt)
                        .or()
                        .gt(AgentMemory::getExpiresAt, LocalDateTime.now()));

        if (types != null && !types.isEmpty()) {
            wrapper.in(AgentMemory::getMemoryType, types);
        }

        wrapper.orderByDesc(AgentMemory::getUpdatedAt)
                .orderByDesc(AgentMemory::getId)
                .last("LIMIT " + limit);

        return mapper.selectList(wrapper);
    }
}
