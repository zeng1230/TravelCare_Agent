package travelcare_agent.agent;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MockIntentClassifier {

    public static final String ORDER_QUERY = "ORDER_QUERY";
    public static final String REFUND_INQUIRY = "REFUND_INQUIRY";

    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("(?i)\\bORD[-_]?\\d+\\b");

    public IntentResult classify(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        String intent = normalized.contains("refund") || normalized.contains("退")
                ? REFUND_INQUIRY
                : ORDER_QUERY;
        return new IntentResult(intent, extractOrderNo(message));
    }

    private static String extractOrderNo(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = ORDER_NO_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group().toUpperCase(Locale.ROOT).replace("_", "-");
    }

    public record IntentResult(String intent, String orderNo) {
    }
}
