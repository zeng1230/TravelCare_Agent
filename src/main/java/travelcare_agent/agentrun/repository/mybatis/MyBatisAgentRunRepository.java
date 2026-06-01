package travelcare_agent.agentrun.repository.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;
import travelcare_agent.agentrun.entity.AgentRun;
import travelcare_agent.agentrun.repository.AgentRunRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisAgentRunRepository implements AgentRunRepository {

    private final MyBatisAgentRunMapper mapper;

    public MyBatisAgentRunRepository(MyBatisAgentRunMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AgentRun save(AgentRun agentRun) {
        if (agentRun.getId() == null) {
            if (agentRun.getCreatedAt() == null) {
                agentRun.setCreatedAt(LocalDateTime.now());
            }
            if (agentRun.getUpdatedAt() == null) {
                agentRun.setUpdatedAt(LocalDateTime.now());
            }
            mapper.insert(agentRun);
        } else {
            agentRun.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(agentRun);
        }
        return agentRun;
    }

    @Override
    public Optional<AgentRun> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    @Override
    public List<AgentRun> findBySessionId(Long sessionId, long pageNo, long pageSize) {
        Page<AgentRun> page = mapper.selectPage(
                Page.of(pageNo, pageSize, false),
                new LambdaQueryWrapper<AgentRun>()
                        .eq(AgentRun::getSessionId, sessionId)
                        .orderByDesc(AgentRun::getCreatedAt)
                        .orderByDesc(AgentRun::getId)
        );
        return page.getRecords();
    }

    @Override
    public long countBySessionId(Long sessionId) {
        return mapper.selectCount(new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getSessionId, sessionId));
    }

    @Override
    public List<AgentRun> findAll() {
        return mapper.selectList(null);
    }
}
