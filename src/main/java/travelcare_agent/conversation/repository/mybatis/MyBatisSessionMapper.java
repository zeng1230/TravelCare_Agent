package travelcare_agent.conversation.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.conversation.entity.Session;

@Mapper
public interface MyBatisSessionMapper extends BaseMapper<Session> {
}
