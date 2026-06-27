package travelcare_agent.outbox;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class RabbitMessageSender implements MessageBrokerClient {
    private final RabbitTemplate rabbitTemplate;
    private final OutboxReliabilityProperties properties;

    public RabbitMessageSender(RabbitTemplate rabbitTemplate, OutboxReliabilityProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public MessageSendResult send(OutboundMessage message) {
        try {
            CorrelationData correlationData = new CorrelationData(
                    "outbox-" + message.outboxEventId() + "-" + UUID.randomUUID());
            rabbitTemplate.convertAndSend(
                    message.exchange(),
                    message.routingKey(),
                    message.payloadJson(),
                    correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(properties.getConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (confirm.isAck()) {
                return MessageSendResult.ack();
            }
            return MessageSendResult.nack("BROKER_NACK");
        } catch (java.util.concurrent.TimeoutException ex) {
            return MessageSendResult.timedOut();
        } catch (Exception ex) {
            return MessageSendResult.nack("BROKER_SEND_FAILED");
        }
    }
}
