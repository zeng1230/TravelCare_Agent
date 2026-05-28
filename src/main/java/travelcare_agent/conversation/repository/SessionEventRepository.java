package travelcare_agent.conversation.repository;

import travelcare_agent.conversation.entity.SessionEvent;

import java.util.List;

public interface SessionEventRepository {

    SessionEvent save(SessionEvent event);

    int nextSeqNo(Long sessionId);

    List<SessionEvent> findBySessionIdOrderBySeqNo(Long sessionId);
}
