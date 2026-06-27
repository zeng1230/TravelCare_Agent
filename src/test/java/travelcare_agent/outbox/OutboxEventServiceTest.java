package travelcare_agent.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventServiceTest {

    @Test
    void createOrReuseUsesDedupeKeyAndDefaultsPayloadVersionV1() {
        InMemoryOutboxEventRepository repository = new InMemoryOutboxEventRepository();
        OutboxEventService service = new OutboxEventService(repository);

        OutboxEvent first = service.createOrReuse(new OutboxEventService.CreateCommand(
                "WORKFLOW_TASK_DISPATCH", "workflow_task", "101",
                "workflow.tasks.routing.key", "{\"taskId\":101}", "workflow_task:101:attempt:1",
                "trace-1", LocalDateTime.parse("2026-06-27T10:00:00")));
        OutboxEvent duplicate = service.createOrReuse(new OutboxEventService.CreateCommand(
                "WORKFLOW_TASK_DISPATCH", "workflow_task", "101",
                "workflow.tasks.routing.key", "{\"taskId\":101}", "workflow_task:101:attempt:1",
                "trace-1", LocalDateTime.parse("2026-06-27T10:01:00")));

        assertThat(duplicate.getId()).isEqualTo(first.getId());
        assertThat(repository.events()).hasSize(1);
        assertThat(first.getPayloadVersion()).isEqualTo("v1");
        assertThat(first.getStatus()).isEqualTo(OutboxEventStatus.NEW);
    }

    @Test
    void stalePublishingEventsAreRecoveredToRetrying() {
        InMemoryOutboxEventRepository repository = new InMemoryOutboxEventRepository();
        OutboxEventService service = new OutboxEventService(repository);
        OutboxEvent event = event("dedupe-1");
        event.setStatus(OutboxEventStatus.PUBLISHING);
        event.setUpdatedAt(LocalDateTime.parse("2026-06-27T10:00:00"));
        repository.save(event);

        int recovered = service.recoverStalePublishing(
                LocalDateTime.parse("2026-06-27T10:10:00"), Duration.ofMinutes(5));

        assertThat(recovered).isEqualTo(1);
        assertThat(repository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEventStatus.RETRYING);
    }

    @Test
    void claimDueEventUsesCompareAndSetSoOnlyOnePublisherWins() {
        InMemoryOutboxEventRepository repository = new InMemoryOutboxEventRepository();
        OutboxEventService service = new OutboxEventService(repository);
        OutboxEvent event = repository.save(event("dedupe-1"));

        Optional<OutboxEvent> firstClaim = service.claimDueEvent(
                event.getId(), LocalDateTime.parse("2026-06-27T10:00:00"));
        Optional<OutboxEvent> secondClaim = service.claimDueEvent(
                event.getId(), LocalDateTime.parse("2026-06-27T10:00:01"));

        assertThat(firstClaim).isPresent();
        assertThat(firstClaim.orElseThrow().getStatus()).isEqualTo(OutboxEventStatus.PUBLISHING);
        assertThat(secondClaim).isEmpty();
    }

    @Test
    void publisherParsesOnlyV1AndMarksConfirmedEventPublished() {
        InMemoryOutboxEventRepository repository = new InMemoryOutboxEventRepository();
        OutboxEventService service = new OutboxEventService(repository);
        OutboxPublisher publisher = new OutboxPublisher(
                service,
                new FakeMessageBrokerClient(MessageSendResult.ack()),
                new OutboxReliabilityProperties());
        OutboxEvent event = repository.save(event("dedupe-1"));

        publisher.publishDueEvents(LocalDateTime.parse("2026-06-27T10:00:00"), 10);

        OutboxEvent saved = repository.findById(event.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(saved.getPublishedAt()).isNotNull();
    }

    @Test
    void publisherLeavesFailedSendVisibleAndRetryable() {
        InMemoryOutboxEventRepository repository = new InMemoryOutboxEventRepository();
        OutboxEventService service = new OutboxEventService(repository);
        OutboxReliabilityProperties properties = new OutboxReliabilityProperties();
        properties.setMaxPublishAttempts(3);
        OutboxPublisher publisher = new OutboxPublisher(
                service,
                new FakeMessageBrokerClient(MessageSendResult.nack("broker-nack")),
                properties);
        OutboxEvent event = repository.save(event("dedupe-1"));

        publisher.publishDueEvents(LocalDateTime.parse("2026-06-27T10:00:00"), 10);

        OutboxEvent saved = repository.findById(event.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.RETRYING);
        assertThat(saved.getAttempts()).isEqualTo(1);
        assertThat(saved.getLastErrorCode()).isEqualTo("broker-nack");
        assertThat(repository.events()).hasSize(1);
    }

    @Test
    void publisherMarksFailedAfterMaxAttempts() {
        InMemoryOutboxEventRepository repository = new InMemoryOutboxEventRepository();
        OutboxEventService service = new OutboxEventService(repository);
        OutboxReliabilityProperties properties = new OutboxReliabilityProperties();
        properties.setMaxPublishAttempts(1);
        OutboxPublisher publisher = new OutboxPublisher(
                service,
                new FakeMessageBrokerClient(MessageSendResult.timedOut()),
                properties);
        OutboxEvent event = repository.save(event("dedupe-1"));

        publisher.publishDueEvents(LocalDateTime.parse("2026-06-27T10:00:00"), 10);

        assertThat(repository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEventStatus.FAILED);
    }

    private static OutboxEvent event(String dedupeKey) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType("WORKFLOW_TASK_DISPATCH");
        event.setAggregateType("workflow_task");
        event.setAggregateId("101");
        event.setRoutingKey("workflow.tasks.routing.key");
        event.setPayloadJson("{\"taskId\":101,\"traceId\":\"trace-1\"}");
        event.setPayloadVersion("v1");
        event.setDedupeKey(dedupeKey);
        event.setStatus(OutboxEventStatus.NEW);
        event.setAttempts(0);
        event.setTraceId("trace-1");
        event.setNextRetryAt(LocalDateTime.parse("2026-06-27T09:59:00"));
        return event;
    }

    private record FakeMessageBrokerClient(MessageSendResult result) implements MessageBrokerClient {
        @Override
        public MessageSendResult send(OutboundMessage message) {
            return result;
        }
    }

    private static final class InMemoryOutboxEventRepository implements OutboxEventRepository {
        private final AtomicLong ids = new AtomicLong(100);
        private final Map<Long, OutboxEvent> byId = new ConcurrentHashMap<>();
        private final Map<String, Long> byDedupe = new ConcurrentHashMap<>();

        @Override
        public OutboxEvent save(OutboxEvent event) {
            if (event.getId() == null) {
                event.setId(ids.incrementAndGet());
            }
            if (event.getCreatedAt() == null) {
                event.setCreatedAt(LocalDateTime.parse("2026-06-27T10:00:00"));
            }
            event.setUpdatedAt(event.getUpdatedAt() == null
                    ? LocalDateTime.parse("2026-06-27T10:00:00") : event.getUpdatedAt());
            byId.put(event.getId(), copy(event));
            byDedupe.put(event.getDedupeKey(), event.getId());
            return copy(event);
        }

        @Override
        public Optional<OutboxEvent> findById(Long id) {
            return Optional.ofNullable(byId.get(id)).map(InMemoryOutboxEventRepository::copy);
        }

        @Override
        public Optional<OutboxEvent> findByDedupeKey(String dedupeKey) {
            Long id = byDedupe.get(dedupeKey);
            return id == null ? Optional.empty() : findById(id);
        }

        @Override
        public List<OutboxEvent> findDueEvents(LocalDateTime now, int limit) {
            return byId.values().stream()
                    .filter(event -> event.getStatus() == OutboxEventStatus.NEW
                            || event.getStatus() == OutboxEventStatus.RETRYING)
                    .filter(event -> event.getNextRetryAt() == null || !event.getNextRetryAt().isAfter(now))
                    .sorted(Comparator.comparing(OutboxEvent::getCreatedAt))
                    .limit(limit)
                    .map(InMemoryOutboxEventRepository::copy)
                    .toList();
        }

        @Override
        public boolean claimForPublishing(Long id, LocalDateTime now) {
            OutboxEvent event = byId.get(id);
            if (event == null || (event.getStatus() != OutboxEventStatus.NEW
                    && event.getStatus() != OutboxEventStatus.RETRYING)) {
                return false;
            }
            event.setStatus(OutboxEventStatus.PUBLISHING);
            event.setUpdatedAt(now);
            return true;
        }

        @Override
        public List<OutboxEvent> findStalePublishing(LocalDateTime before) {
            return byId.values().stream()
                    .filter(event -> event.getStatus() == OutboxEventStatus.PUBLISHING)
                    .filter(event -> event.getUpdatedAt().isBefore(before))
                    .map(InMemoryOutboxEventRepository::copy)
                    .toList();
        }

        List<OutboxEvent> events() {
            return new ArrayList<>(byId.values());
        }

        private static OutboxEvent copy(OutboxEvent source) {
            OutboxEvent event = new OutboxEvent();
            event.setId(source.getId());
            event.setEventType(source.getEventType());
            event.setAggregateType(source.getAggregateType());
            event.setAggregateId(source.getAggregateId());
            event.setRoutingKey(source.getRoutingKey());
            event.setPayloadJson(source.getPayloadJson());
            event.setPayloadVersion(source.getPayloadVersion());
            event.setDedupeKey(source.getDedupeKey());
            event.setStatus(source.getStatus());
            event.setAttempts(source.getAttempts());
            event.setNextRetryAt(source.getNextRetryAt());
            event.setLastErrorCode(source.getLastErrorCode());
            event.setTraceId(source.getTraceId());
            event.setCreatedAt(source.getCreatedAt());
            event.setUpdatedAt(source.getUpdatedAt());
            event.setPublishedAt(source.getPublishedAt());
            return event;
        }
    }
}
