package travelcare_agent.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.observability.TravelCareMetrics;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class OutboxEventService {
    public static final String PAYLOAD_VERSION_V1 = "v1";

    private final OutboxEventRepository repository;
    private final TravelCareMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public OutboxEventService(OutboxEventRepository repository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) TravelCareMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
        if (metrics != null) metrics.gauge("travelcare.outbox.backlog", repository::countBacklog);
    }

    public OutboxEventService(OutboxEventRepository repository) {
        this(repository, null);
    }

    @Transactional
    public OutboxEvent createOrReuse(CreateCommand command) {
        return repository.findByDedupeKey(command.dedupeKey()).orElseGet(() -> {
            OutboxEvent event = new OutboxEvent();
            event.setEventType(command.eventType());
            event.setAggregateType(command.aggregateType());
            event.setAggregateId(command.aggregateId());
            event.setRoutingKey(command.routingKey());
            event.setPayloadJson(command.payloadJson());
            event.setPayloadVersion(PAYLOAD_VERSION_V1);
            event.setDedupeKey(command.dedupeKey());
            event.setStatus(OutboxEventStatus.NEW);
            event.setAttempts(0);
            event.setNextRetryAt(command.nextRetryAt());
            event.setTraceId(command.traceId());
            OutboxEvent saved = repository.save(event);
            if (metrics != null) metrics.outboxCreated(saved.getEventType());
            return saved;
        });
    }

    public List<OutboxEvent> findDueEvents(LocalDateTime now, int limit) {
        return repository.findDueEvents(now, limit);
    }

    @Transactional
    public Optional<OutboxEvent> claimDueEvent(Long id, LocalDateTime now) {
        if (!repository.claimForPublishing(id, now)) {
            return Optional.empty();
        }
        return repository.findById(id);
    }

    @Transactional
    public void markPublished(Long id, LocalDateTime now) {
        OutboxEvent event = repository.findById(id).orElseThrow();
        event.setStatus(OutboxEventStatus.PUBLISHED);
        event.setPublishedAt(now);
        event.setLastErrorCode(null);
        repository.save(event);
        if (metrics != null) {
            Duration latency = event.getCreatedAt() == null ? Duration.ZERO
                    : Duration.between(event.getCreatedAt(), now);
            metrics.outboxPublished(event.getEventType(), latency);
        }
    }

    @Transactional
    public void markPublishFailed(Long id, String errorCode, LocalDateTime now, int maxAttempts, Duration retryDelay) {
        OutboxEvent event = repository.findById(id).orElseThrow();
        int attempts = event.getAttempts() == null ? 1 : event.getAttempts() + 1;
        event.setAttempts(attempts);
        event.setLastErrorCode(safeErrorCode(errorCode));
        if (attempts >= maxAttempts) {
            event.setStatus(OutboxEventStatus.FAILED);
            event.setNextRetryAt(null);
        } else {
            event.setStatus(OutboxEventStatus.RETRYING);
            event.setNextRetryAt(now.plus(retryDelay));
        }
        repository.save(event);
        if (metrics != null) {
            if (event.getStatus() == OutboxEventStatus.FAILED) {
                metrics.outboxFailed(event.getEventType(), event.getLastErrorCode());
            } else {
                metrics.outboxRetry(event.getEventType(), event.getLastErrorCode());
            }
        }
    }

    @Transactional
    public int recoverStalePublishing(LocalDateTime now, Duration staleAfter) {
        List<OutboxEvent> stale = repository.findStalePublishing(now.minus(staleAfter));
        for (OutboxEvent event : stale) {
            event.setStatus(OutboxEventStatus.RETRYING);
            event.setLastErrorCode("STALE_PUBLISHING_RECOVERED");
            event.setNextRetryAt(now);
            repository.save(event);
        }
        return stale.size();
    }

    private static String safeErrorCode(String errorCode) {
        return errorCode != null && errorCode.matches("[A-Za-z0-9_\\-]{1,64}")
                ? errorCode : "BROKER_SEND_FAILED";
    }

    public record CreateCommand(
            String eventType,
            String aggregateType,
            String aggregateId,
            String routingKey,
            String payloadJson,
            String dedupeKey,
            String traceId,
            LocalDateTime nextRetryAt
    ) {
    }
}
