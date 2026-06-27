package travelcare_agent.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SecurityTestTokenFactory {
    public static final String SECRET = "test-jwt-secret-with-at-least-32-bytes";

    private SecurityTestTokenFactory() {
    }

    public static String bearer(Long userId, String tenantId, String... roles) {
        return "Bearer " + token(userId, tenantId, List.of(roles));
    }

    private static String token(Long userId, String tenantId, List<String> roles) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"sub\":\"" + userId + "\",\"userId\":" + userId
                + ",\"tenantId\":\"" + tenantId + "\",\"roles\":["
                + roles.stream().map(role -> "\"" + role + "\"").reduce((a, b) -> a + "," + b).orElse("")
                + "],\"iat\":" + Instant.now().getEpochSecond()
                + ",\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond()
                + ",\"iss\":\"travelcare-agent\"}";
        String unsigned = base64Url(header) + "." + base64Url(payload);
        return unsigned + "." + sign(unsigned);
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String unsigned) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
