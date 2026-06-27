package travelcare_agent.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedactionServiceTest {

    private final RedactionService service = new RedactionService();

    @Test
    void redactsSensitiveJsonFieldsAndFreeTextPatterns() {
        String input = "{\"phone\":\"13812345678\",\"email\":\"user@example.com\","
                + "\"idCard\":\"11010519491231002X\",\"token\":\"abc\",\"password\":\"pw\","
                + "\"secret\":\"s\",\"apiKey\":\"key\",\"authorization\":\"Bearer raw-token\","
                + "\"cookie\":\"sid=1\",\"note\":\"mail other@example.com phone 13912345678 "
                + "id 11010519491231002X Bearer another-token\"}";

        RedactionService.RedactionResult result = service.redact(input);

        assertThat(result.value()).doesNotContain(
                "13812345678", "user@example.com", "11010519491231002X", "abc", "pw",
                "raw-token", "other@example.com", "13912345678", "another-token"
        );
        assertThat(result.value()).contains("[REDACTED]");
        assertThat(result.redactedCount()).isGreaterThanOrEqualTo(13);
    }

    @Test
    void redactsJwtSecretsAndStackTraceText() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMDAxIn0.signature";
        String input = "{\"providerSecret\":\"deepseek-secret\",\"api_key\":\"raw-key\","
                + "\"message\":\"Authorization: Bearer " + jwt + "\\n"
                + "java.lang.IllegalStateException: provider raw failure\\n"
                + "\\tat travelcare_agent.agent.Provider.call(Provider.java:42)\"}";

        RedactionService.RedactionResult result = service.redact(input);

        assertThat(result.value()).doesNotContain("deepseek-secret", "raw-key", jwt, "Provider.java:42");
        assertThat(result.value()).contains("[REDACTED]");
    }
}
