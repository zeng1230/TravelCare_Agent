package travelcare_agent.agent.safety;

import java.util.List;
import java.util.regex.Pattern;

public class SensitiveDataLeakageDetector {
    private static final List<Pattern> BLOCKED = List.of(
            Pattern.compile("(?i)authorization\\s*[:=]|bearer\\s+[a-z0-9._~+/-]+"),
            Pattern.compile("(?i)(api[_ -]?key|secret|password)\\s*[:=]\\s*\\S+"),
            Pattern.compile("(?i)java\\.lang\\.|exception(?:\\s|:)|\\bat\\s+[a-z0-9_.$]+\\([^)]*\\.java:\\d+\\)"),
            Pattern.compile("(?i)provider raw response|http response body|system prompt"),
            Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)"),
            Pattern.compile("(?<!\\d)\\d{17}[0-9xX](?!\\w)")
    );

    public boolean containsSensitiveLeakage(String text) {
        if (text == null || text.isBlank()) return false;
        return BLOCKED.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }
}
