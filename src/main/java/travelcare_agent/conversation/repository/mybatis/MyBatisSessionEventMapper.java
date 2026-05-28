package travelcare_agent.conversation.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import travelcare_agent.conversation.entity.SessionEvent;

@Mapper
public interface MyBatisSessionEventMapper extends BaseMapper<SessionEvent> {

    @Select("select coalesce(max(seq_no), 0) + 1 from session_events where session_id = #{sessionId}")
    int nextSeqNo(Long sessionId);
}
