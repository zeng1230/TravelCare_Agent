package travelcare_agent.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
public class RedactionService {
    private static final String MASK = "[REDACTED]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "phone", "email", "idcard", "token", "accesstoken",
            "refreshtoken", "password", "secret", "providersecret",
            "apikey", "authorization", "cookie",
            "rawprompt", "rawprovideroutput",
            "rawmodeloutput", "rawtooloutput",
            "credential", "signature"
    );
    private static final Set<String> FORBIDDEN_METRIC_KEYS = Set.of(
            "userid", "tenantid", "sessionid", "workflowid", "orderno",
            "traceid", "prompt", "token", "secret"
    );
    private static final Pattern EMAIL = Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\w)");
    private static final Pattern BANK_CARD = Pattern.compile("(?i)(bank|card|银行卡|卡号)[^\\n\\r]{0,32}\\b\\d{16,19}\\b");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile("(?i)(authorization|api[_-]?key|provider[_-]?secret|secret|token|credential|password|signature)\\s*\"?\\s*[:=]\\s*(\"[^\\n\\r`]*?\"|[^\\s,;\\\"})]+)");
    private static final Pattern RAW_TEXT = Pattern.compile("(?i)raw[_ -]?(prompt|provider[_ -]?output|model[_ -]?output|tool[_ -]?output|response)\\s*\"?\\s*[:=]\\s*(\"[^\\n\\r`]*?\"|[^\\n\\r`\\\",})]*)");
    private static final Pattern STACK_FRAME = Pattern.compile("at\\s+[\\w.$_]+\\([^\\n\\r\\\\]*\\.java:\\d+\\)");
    private static final Pattern SOURCE_URI_TEXT = Pattern.compile("https?://[^\\s`\\\"'<>]+");
    private static final Pattern UUID = Pattern.compile("(?i).*\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b.*");
    private static final Pattern LONG_NUMBER = Pattern.compile(".*\\b\\d{8,}\\b.*");
    private static final Pattern ORDER_NO = Pattern.compile("(?i).*\\bORD[-_]?\\d+\\b.*");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public RedactionResult redact(String value) {
        if (value == null) return new RedactionResult(null, 0);
        AtomicInteger count = new AtomicInteger();
        try {
            JsonNode node = objectMapper.readTree(value);
            redactNode(node, count);
            String json = objectMapper.writeValueAsString(node);
            return redactText(json, count);
        } catch (Exception ignored) {
            return redactText(value, count);
        }
    }

    public RedactionResult redactObject(Object value) {
        if (value == null) return new RedactionResult("{}", 0);
        try { return redact(objectMapper.writeValueAsString(value)); }
        catch (Exception ex) { return redact(String.valueOf(value)); }
    }

    private void redactNode(JsonNode node, AtomicInteger count) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                if (SENSITIVE_KEYS.contains(normalizeKey(key))) {
                    object.put(field.getKey(), MASK); count.incrementAndGet();
                } else if ("sourceuri".equals(normalizeKey(key)) && field.getValue().isTextual()) {
                    String sanitized = sanitizeSourceUri(field.getValue().asText());
                    if (!sanitized.equals(field.getValue().asText())) count.incrementAndGet();
                    object.put(field.getKey(), sanitized);
                } else if (field.getValue().isTextual()) {
                    RedactionResult result = redactText(field.getValue().asText(), count);
                    object.put(field.getKey(), result.value());
                } else redactNode(field.getValue(), count);
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(child -> redactNode(child, count));
        }
    }

    private RedactionResult redactText(String input, AtomicInteger initial) {
        String value = input;
        value = replaceSourceUris(value, initial);
        value = replace(value, RAW_TEXT, initial);
        value = replace(value, BEARER, initial);
        value = replace(value, JWT, initial);
        value = replace(value, KEY_VALUE_SECRET, initial);
        value = replace(value, STACK_FRAME, initial);
        value = replace(value, EMAIL, initial);
        value = replace(value, PHONE, initial);
        value = replace(value, ID_CARD, initial);
        value = replace(value, BANK_CARD, initial);
        return new RedactionResult(value, initial.get());
    }

    private String replace(String value, Pattern pattern, AtomicInteger count) {
        var matcher = pattern.matcher(value); StringBuffer buffer = new StringBuffer();
        while (matcher.find()) { count.incrementAndGet(); matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(MASK)); }
        matcher.appendTail(buffer); return buffer.toString();
    }

    private String replaceSourceUris(String value, AtomicInteger count) {
        var matcher = SOURCE_URI_TEXT.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group();
            String sanitized = sanitizeSourceUri(original);
            if (!original.equals(sanitized)) count.incrementAndGet();
            matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(sanitized));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    public boolean containsSensitiveLeakage(Object value) {
        if (value == null) return false;
        String text = String.valueOf(value);
        if (text.isBlank()) return false;
        RedactionResult result = redact(text);
        return result.redactedCount() > 0 && !text.equals(result.value());
    }

    public String sanitizeSourceUri(String value) {
        if (value == null || value.isBlank()) return value;
        try {
            URI uri = URI.create(value);
            String query = sanitizeQuery(uri.getRawQuery());
            return new URI(uri.getScheme(), uri.getRawAuthority(), uri.getRawPath(), query, null).toString();
        } catch (Exception ex) {
            return redact(value).value();
        }
    }

    public String sanitizeLogField(String value, int limit) {
        if (value == null) return "";
        String redacted = redact(value).value();
        int max = limit <= 0 ? 256 : limit;
        return redacted.length() > max ? redacted.substring(0, max) : redacted;
    }

    public String safeMetricTagValue(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String trimmed = value.trim();
        String lower = normalizeKey(trimmed);
        if (FORBIDDEN_METRIC_KEYS.stream().anyMatch(lower::contains)
                || UUID.matcher(trimmed).matches()
                || LONG_NUMBER.matcher(trimmed).matches()
                || BEARER.matcher(trimmed).matches()
                || JWT.matcher(trimmed).matches()
                || EMAIL.matcher(trimmed).matches()
                || PHONE.matcher(trimmed).matches()
                || ID_CARD.matcher(trimmed).matches()
                || ORDER_NO.matcher(trimmed).matches()
                || containsSensitiveLeakage(trimmed)) {
            return "REDACTED";
        }
        return trimmed.length() > 64 ? "REDACTED" : trimmed;
    }

    public boolean forbiddenMetricTagKey(String key) {
        String normalized = normalizeKey(key);
        return FORBIDDEN_METRIC_KEYS.stream().anyMatch(normalized::contains);
    }

    public String safeFieldName(String key) {
        return SENSITIVE_KEYS.contains(normalizeKey(key)) ? MASK : key;
    }

    private String sanitizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return null;
        List<String> kept = new java.util.ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            String key = pair;
            String value = "";
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                key = pair.substring(0, eq);
                value = pair.substring(eq + 1);
            }
            if (isSecretQueryKey(key)) continue;
            kept.add(key + (eq >= 0 ? "=" + URLEncoder.encode(value, StandardCharsets.UTF_8) : ""));
        }
        return kept.isEmpty() ? null : String.join("&", kept);
    }

    private boolean isSecretQueryKey(String key) {
        String normalized = normalizeKey(key);
        return normalized.contains("token") || normalized.contains("secret") || normalized.contains("key")
                || normalized.contains("signature") || normalized.contains("authorization")
                || normalized.contains("credential") || normalized.contains("password");
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.replace("_", "").replace("-", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }

    public record RedactionResult(String value, int redactedCount) {}
}
