package travelcare_agent.conversation.repository.mybatis;

import org.springframework.stereotype.Repository;
import travelcare_agent.conversation.entity.Session;
import travelcare_agent.conversation.repository.SessionRepository;

import java.util.Optional;

@Repository
public class MyBatisSessionRepository implements SessionRepository {

    private final MyBatisSessionMapper mapper;

    public MyBatisSessionRepository(MyBatisSessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Session save(Session session) {
        if (session.getId() == null) {
            mapper.insert(session);
        } else {
            mapper.updateById(session);
        }
        return session;
    }

    @Override
    public Optional<Session> findById(Long sessionId) {
        return Optional.ofNullable(mapper.selectById(sessionId));
    }
}
