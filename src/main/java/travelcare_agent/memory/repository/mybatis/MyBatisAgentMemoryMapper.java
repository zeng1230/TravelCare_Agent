package travelcare_agent.memory.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.memory.entity.AgentMemory;

@Mapper
public interface MyBatisAgentMemoryMapper extends BaseMapper<AgentMemory> {
}
