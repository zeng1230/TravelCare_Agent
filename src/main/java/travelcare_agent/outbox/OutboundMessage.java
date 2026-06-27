package travelcare_agent.outbox;

public record OutboundMessage(
        Long outboxEventId,
        String eventType,
        String payloadVersion,
        String exchange,
        String routingKey,
        String payloadJson,
        String traceId
) {
}
