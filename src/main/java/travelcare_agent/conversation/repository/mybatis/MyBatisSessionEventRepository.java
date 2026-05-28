package travelcare_agent.conversation.repository.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.conversation.repository.SessionEventRepository;

import java.util.List;

@Repository
public class MyBatisSessionEventRepository implements SessionEventRepository {

    private final MyBatisSessionEventMapper mapper;

    public MyBatisSessionEventRepository(MyBatisSessionEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public SessionEvent save(SessionEvent event) {
        mapper.insert(event);
        return event;
    }

    @Override
    public int nextSeqNo(Long sessionId) {
        return mapper.nextSeqNo(sessionId);
    }

    @Override
    public List<SessionEvent> findBySessionIdOrderBySeqNo(Long sessionId) {
        return mapper.selectList(new LambdaQueryWrapper<SessionEvent>()
                .eq(SessionEvent::getSessionId, sessionId)
                .orderByAsc(SessionEvent::getSeqNo));
    }
}
