package travelcare_agent.agent.provider;

public class ModelCallException extends RuntimeException {

    private final String code;

    public ModelCallException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ModelCallException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
