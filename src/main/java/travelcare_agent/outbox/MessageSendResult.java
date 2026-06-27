package travelcare_agent.outbox;

public record MessageSendResult(boolean acknowledged, boolean timeout, String errorCode) {
    public static MessageSendResult ack() {
        return new MessageSendResult(true, false, null);
    }

    public static MessageSendResult nack(String errorCode) {
        return new MessageSendResult(false, false, errorCode == null ? "BROKER_NACK" : errorCode);
    }

    public static MessageSendResult timedOut() {
        return new MessageSendResult(false, true, "BROKER_CONFIRM_TIMEOUT");
    }
}
