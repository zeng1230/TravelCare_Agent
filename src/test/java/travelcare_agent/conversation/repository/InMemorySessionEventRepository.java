package travelcare_agent.conversation.repository;

import travelcare_agent.conversation.entity.SessionEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemorySessionEventRepository implements SessionEventRepository {

    private final AtomicLong ids = new AtomicLong(2000);
    private final Map<Long, List<SessionEvent>> eventsBySession = new ConcurrentHashMap<>();

    @Override
    public synchronized SessionEvent save(SessionEvent event) {
        if (event.getId() == null) {
            event.setId(ids.incrementAndGet());
        }
        eventsBySession.computeIfAbsent(event.getSessionId(), ignored -> new ArrayList<>()).add(event);
        return event;
    }

    @Override
    public synchronized int nextSeqNo(Long sessionId) {
        return eventsBySession.getOrDefault(sessionId, List.of()).stream()
                .map(SessionEvent::getSeqNo)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    @Override
    public List<SessionEvent> findBySessionIdOrderBySeqNo(Long sessionId) {
        return eventsBySession.getOrDefault(sessionId, List.of()).stream()
                .sorted(Comparator.comparing(SessionEvent::getSeqNo))
                .toList();
    }
}
