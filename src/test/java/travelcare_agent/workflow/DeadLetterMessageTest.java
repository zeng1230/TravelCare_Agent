package travelcare_agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterMessageTest {

    @Test
    void serializesOnlyAllowedTroubleshootingFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DeadLetterMessage message = new DeadLetterMessage(
                101L, 201L, 301L, "trace-1", "SYSTEM_ERROR", 3,
                "max attempts reached", 401L, LocalDateTime.parse("2026-06-27T10:00:00"));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(message));
        Set<String> allowed = Set.of(
                "taskId", "workflowId", "toolCallId", "traceId", "failureCode",
                "attempts", "deadLetterReason", "outboxEventId", "createdAt");
        Iterator<String> names = json.fieldNames();
        while (names.hasNext()) {
            assertThat(allowed).contains(names.next());
        }
        assertThat(json.toString()).doesNotContain("request_json")
                .doesNotContain("response_json")
                .doesNotContain("prompt")
                .doesNotContain("provider")
                .doesNotContain("token")
                .doesNotContain("secret");
    }
}
