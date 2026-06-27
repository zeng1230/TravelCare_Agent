package travelcare_agent.outbox;

public interface MessageBrokerClient {
    MessageSendResult send(OutboundMessage message);
}
