package travelcare_agent.conversation.repository;

import travelcare_agent.conversation.entity.Session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemorySessionRepository implements SessionRepository {

    private final AtomicLong ids = new AtomicLong(1000);
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public Session save(Session session) {
        if (session.getId() == null) {
            session.setId(ids.incrementAndGet());
        }
        sessions.put(session.getId(), session);
        return session;
    }

    @Override
    public Optional<Session> findById(Long sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }
}
