package travelcare_agent.tool.repository.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;
import travelcare_agent.tool.entity.ToolCall;
import travelcare_agent.tool.repository.ToolCallRepository;

import java.util.Optional;

@Repository
public class MyBatisToolCallRepository implements ToolCallRepository {

    private final MyBatisToolCallMapper mapper;

    public MyBatisToolCallRepository(MyBatisToolCallMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ToolCall save(ToolCall toolCall) {
        if (toolCall.getId() == null) {
            mapper.insert(toolCall);
        } else {
            mapper.updateById(toolCall);
        }
        return toolCall;
    }

    @Override
    public Optional<ToolCall> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    @Override
    public Optional<ToolCall> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<ToolCall>()
                .eq(ToolCall::getIdempotencyKey, idempotencyKey)
                .last("limit 1")));
    }
}
