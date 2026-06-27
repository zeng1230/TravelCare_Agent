package travelcare_agent.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class JwtTokenService {
    private final JwtProperties properties;
    private final ObjectMapper objectMapper;

    public JwtTokenService(JwtProperties properties, ObjectMapper objectMapper, Environment environment) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.properties.validate(environment);
    }

    public String issue(Long userId, String tenantId, List<String> roles) {
        long now = Instant.now().getEpochSecond();
        String normalizedTenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        List<String> normalizedRoles = roles == null || roles.isEmpty() ? List.of("USER") : roles;
        String roleJson = normalizedRoles.stream()
                .map(role -> "\"" + escape(role) + "\"")
                .collect(Collectors.joining(","));
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"sub\":\"" + userId + "\",\"userId\":" + userId
                + ",\"tenantId\":\"" + escape(normalizedTenant) + "\",\"roles\":[" + roleJson + "]"
                + ",\"iat\":" + now + ",\"exp\":" + (now + properties.getTtlSeconds())
                + ",\"iss\":\"" + escape(properties.getIssuer()) + "\"}";
        String unsigned = base64Url(header) + "." + base64Url(payload);
        return unsigned + "." + sign(unsigned);
    }

    public CurrentUser parse(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            String unsigned = parts[0] + "." + parts[1];
            if (!constantEquals(sign(unsigned), parts[2])) {
                return null;
            }
            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (!properties.getIssuer().equals(payload.path("iss").asText())) {
                return null;
            }
            if (payload.path("exp").asLong(0) < Instant.now().getEpochSecond()) {
                return null;
            }
            Long userId = payload.path("userId").isMissingNode() ? null : payload.path("userId").asLong();
            String tenantId = payload.path("tenantId").asText(null);
            Set<String> roles = new LinkedHashSet<>();
            payload.path("roles").forEach(role -> roles.add(role.asText()));
            if (userId == null || tenantId == null || roles.isEmpty()) {
                return null;
            }
            return new CurrentUser(userId, tenantId, roles);
        } catch (Exception ex) {
            return null;
        }
    }

    private String sign(String unsigned) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign JWT", ex);
        }
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean constantEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
