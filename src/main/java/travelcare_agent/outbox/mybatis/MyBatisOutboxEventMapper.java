package travelcare_agent.outbox.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.outbox.OutboxEvent;

@Mapper
public interface MyBatisOutboxEventMapper extends BaseMapper<OutboxEvent> {
}
