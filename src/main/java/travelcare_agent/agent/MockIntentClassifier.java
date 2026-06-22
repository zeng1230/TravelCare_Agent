package travelcare_agent.agent;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MockIntentClassifier {

    public static final String ORDER_QUERY = "ORDER_QUERY";
    public static final String REFUND_INQUIRY = "REFUND_INQUIRY";
    public static final String FAQ = "FAQ";
    public static final String SOP = "SOP";
    public static final String KNOWLEDGE_QUERY = "KNOWLEDGE_QUERY";

    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("(?i)\\bORD[-_]?\\d+\\b");

    public IntentResult classify(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        String intent;
        if (normalized.contains("sop")) {
            intent = SOP;
        } else if (normalized.contains("policy") || normalized.contains("faq")) {
            intent = FAQ;
        } else if (normalized.contains("knowledge")) {
            intent = KNOWLEDGE_QUERY;
        } else if (normalized.contains("refund") || normalized.contains("退")) {
            intent = REFUND_INQUIRY;
        } else {
            intent = ORDER_QUERY;
        }
        return new IntentResult(intent, extractOrderNo(message));
    }

    private static String extractOrderNo(String message) {
        if (message == null) return null;
        Matcher matcher = ORDER_NO_PATTERN.matcher(message);
        if (!matcher.find()) return null;
        return matcher.group().toUpperCase(Locale.ROOT).replace("_", "-");
    }

    public record IntentResult(String intent, String orderNo) {
    }
}
