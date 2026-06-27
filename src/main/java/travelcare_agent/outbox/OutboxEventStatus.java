package travelcare_agent.outbox;

public enum OutboxEventStatus {
    NEW,
    PUBLISHING,
    PUBLISHED,
    RETRYING,
    FAILED
}
