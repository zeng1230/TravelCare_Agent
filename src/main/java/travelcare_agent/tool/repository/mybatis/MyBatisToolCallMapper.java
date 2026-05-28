package travelcare_agent.tool.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.tool.entity.ToolCall;

@Mapper
public interface MyBatisToolCallMapper extends BaseMapper<ToolCall> {
}
