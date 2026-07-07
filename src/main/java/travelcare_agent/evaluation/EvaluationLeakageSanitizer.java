package travelcare_agent.evaluation;

import java.util.List;
import java.util.regex.Pattern;

public final class EvaluationLeakageSanitizer {
    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            Pattern.compile("(?i)raw[_ -]?(prompt|provider[_ -]?output|model[_ -]?output|tool[_ -]?output)\\s*[:=][^\\n`]*"),
            Pattern.compile("(?i)Authorization\\s*:\\s*Bearer\\s+[^\\s`]+"),
            Pattern.compile("(?i)\\bBearer\\s+[^\\s`]+"),
            Pattern.compile("(?i)\\b(api[_-]?key|token|secret|credential|password|signature)\\s*[:=][^\\s`&]+"),
            Pattern.compile("(?i)([?&])(api[_-]?key|token|secret|credential|authorization|signature)=[^\\s&#`]+"),
            Pattern.compile("\\b1[3-9]\\d{9}\\b"),
            Pattern.compile("\\b\\d{17}[0-9Xx]\\b"),
            Pattern.compile("(?i)(bank|card|银行卡|卡号)[^\\n]{0,32}\\b\\d{16,19}\\b")
    );

    private EvaluationLeakageSanitizer() {
    }

    public static boolean containsSensitiveLeakage(Object value) {
        if (value == null) return false;
        String text = String.valueOf(value);
        if (text.isBlank()) return false;
        return BLOCKED_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }

    public static String redact(String value) {
        if (value == null || value.isBlank()) return value;
        String redacted = value;
        for (Pattern pattern : BLOCKED_PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll("[REDACTED]");
        }
        return redacted;
    }
}
