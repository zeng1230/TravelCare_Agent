package travelcare_agent.trace.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.trace.entity.TraceEvent;

@Mapper
public interface MyBatisTraceEventMapper extends BaseMapper<TraceEvent> {
}
