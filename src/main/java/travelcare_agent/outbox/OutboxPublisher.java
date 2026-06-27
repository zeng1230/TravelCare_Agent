package travelcare_agent.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import travelcare_agent.config.RabbitMqConfig;

import java.time.LocalDateTime;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventService outboxEventService;
    private final MessageBrokerClient brokerClient;
    private final OutboxReliabilityProperties properties;

    public OutboxPublisher(
            OutboxEventService outboxEventService,
            MessageBrokerClient brokerClient,
            OutboxReliabilityProperties properties) {
        this.outboxEventService = outboxEventService;
        this.brokerClient = brokerClient;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${travelcare.async.outbox.scan-delay-ms:5000}")
    public void scheduledPublish() {
        publishDueEvents(LocalDateTime.now(), 50);
    }

    public void publishDueEvents(LocalDateTime now, int limit) {
        outboxEventService.recoverStalePublishing(now, properties.getStalePublishingAfter());
        for (OutboxEvent due : outboxEventService.findDueEvents(now, limit)) {
            outboxEventService.claimDueEvent(due.getId(), now).ifPresent(event -> publishClaimed(event, now));
        }
    }

    private void publishClaimed(OutboxEvent event, LocalDateTime now) {
        if (!OutboxEventService.PAYLOAD_VERSION_V1.equals(event.getPayloadVersion())) {
            outboxEventService.markPublishFailed(event.getId(), "UNSUPPORTED_PAYLOAD_VERSION",
                    now, properties.getMaxPublishAttempts(), properties.getRetryDelay());
            return;
        }
        OutboundMessage message = new OutboundMessage(
                event.getId(),
                event.getEventType(),
                event.getPayloadVersion(),
                exchangeFor(event),
                event.getRoutingKey(),
                event.getPayloadJson(),
                event.getTraceId());
        MessageSendResult result = brokerClient.send(message);
        if (result.acknowledged()) {
            outboxEventService.markPublished(event.getId(), now);
            log.info("Outbox event published eventId={} traceId={} eventType={}",
                    event.getId(), event.getTraceId(), event.getEventType());
        } else {
            String code = result.timeout() ? "BROKER_CONFIRM_TIMEOUT" : result.errorCode();
            outboxEventService.markPublishFailed(event.getId(), code,
                    now, properties.getMaxPublishAttempts(), properties.getRetryDelay());
            log.warn("Outbox event publish failed eventId={} traceId={} errorCode={}",
                    event.getId(), event.getTraceId(), code);
        }
    }

    private String exchangeFor(OutboxEvent event) {
        if ("WORKFLOW_TASK_DEAD_LETTER".equals(event.getEventType())) {
            return RabbitMqConfig.EXCHANGE_WORKFLOW_DLX;
        }
        return RabbitMqConfig.EXCHANGE_WORKFLOW;
    }
}
