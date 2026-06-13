package travelcare_agent.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
public class RedactionService {
    private static final String MASK = "[REDACTED]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "phone", "email", "idcard", "token", "password", "secret", "apikey", "authorization", "cookie"
    );
    private static final Pattern EMAIL = Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\w)");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*");
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
                if (SENSITIVE_KEYS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
                    object.put(field.getKey(), MASK); count.incrementAndGet();
                } else redactNode(field.getValue(), count);
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(child -> redactNode(child, count));
        }
    }

    private RedactionResult redactText(String input, AtomicInteger initial) {
        String value = input;
        value = replace(value, BEARER, initial);
        value = replace(value, EMAIL, initial);
        value = replace(value, PHONE, initial);
        value = replace(value, ID_CARD, initial);
        return new RedactionResult(value, initial.get());
    }

    private String replace(String value, Pattern pattern, AtomicInteger count) {
        var matcher = pattern.matcher(value); StringBuffer buffer = new StringBuffer();
        while (matcher.find()) { count.incrementAndGet(); matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(MASK)); }
        matcher.appendTail(buffer); return buffer.toString();
    }

    public record RedactionResult(String value, int redactedCount) {}
}
