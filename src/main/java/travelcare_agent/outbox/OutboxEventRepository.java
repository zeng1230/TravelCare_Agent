package travelcare_agent.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository {
    OutboxEvent save(OutboxEvent event);
    Optional<OutboxEvent> findById(Long id);
    Optional<OutboxEvent> findByDedupeKey(String dedupeKey);
    List<OutboxEvent> findDueEvents(LocalDateTime now, int limit);
    boolean claimForPublishing(Long id, LocalDateTime now);
    List<OutboxEvent> findStalePublishing(LocalDateTime before);
    default long countBacklog() { return 0; }
}
