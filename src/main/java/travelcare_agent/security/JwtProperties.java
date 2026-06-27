package travelcare_agent.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

@ConfigurationProperties(prefix = "travelcare.jwt")
public class JwtProperties {
    private static final String DEFAULT_SECRET = "change-me-in-local-env";

    private String issuer = "travelcare-agent";
    private String secret = DEFAULT_SECRET;
    private long ttlSeconds = 7200;

    public void validate(Environment environment) {
        boolean localLike = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equalsIgnoreCase("local")
                        || profile.equalsIgnoreCase("dev")
                        || profile.equalsIgnoreCase("test"));
        if (!localLike && (secret == null || secret.isBlank()
                || DEFAULT_SECRET.equals(secret)
                || secret.getBytes(StandardCharsets.UTF_8).length < 32)) {
            throw new IllegalStateException("Production JWT secret must be configured and at least 32 bytes");
        }
        if (secret != null && !secret.isBlank() && secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 bytes for HS256");
        }
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
