package travelcare_agent.agent.safety;

public class ModelOutputParseException extends RuntimeException {
    private final String reasonCode;

    public ModelOutputParseException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public ModelOutputParseException(String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
