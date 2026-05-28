package travelcare_agent.conversation.repository;

import travelcare_agent.conversation.entity.Session;

import java.util.Optional;

public interface SessionRepository {

    Session save(Session session);

    Optional<Session> findById(Long sessionId);
}
