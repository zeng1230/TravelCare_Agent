package travelcare_agent.agentrun.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.agentrun.entity.AgentRun;

@Mapper
public interface MyBatisAgentRunMapper extends BaseMapper<AgentRun> {
}
