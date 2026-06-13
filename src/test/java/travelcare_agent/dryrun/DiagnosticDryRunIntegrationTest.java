package travelcare_agent.dryrun;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import travelcare_agent.adapter.order.MockOrderAdapter;
import travelcare_agent.agent.provider.DeepSeekAgentProvider;
import travelcare_agent.conversation.service.SessionService;
import travelcare_agent.trace.TraceQueryService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
class DiagnosticDryRunIntegrationTest {
    private static final List<String> BUSINESS_TABLES = List.of(
            "sessions", "session_events", "workflows", "workflow_steps", "tool_calls", "idempotency_keys",
            "audit_logs", "human_review_cases", "workflow_tasks", "refund_cases", "agent_runs"
    );

    @Autowired private SessionService sessionService;
    @Autowired private DiagnosticDryRunService dryRunService;
    @Autowired private TraceDiffService traceDiffService;
    @Autowired private TraceQueryService traceQueryService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @SpyBean private MockOrderAdapter orderAdapter;
    @SpyBean private DeepSeekAgentProvider deepSeekAgentProvider;
    @MockBean private RabbitTemplate rabbitTemplate;

    @Test
    void dryRunWritesOnlyTraceAndDiffTables() {
        Long sessionId = sessionService.createSession(1001L, "WEB").sessionId();
        SessionService.SendMessageResult original = sessionService.sendMessage(
                sessionId, "Can I refund order ORD-1001?", "stage7b-original-" + System.nanoTime(), false);
        Map<String, Long> before = counts();
        var originalSnapshots = traceQueryService.get(original.traceId()).snapshots();
        reset(orderAdapter, deepSeekAgentProvider, rabbitTemplate);

        DryRunResult result = dryRunService.run(original.traceId(), new DryRunRequest("integration-test", "mock", true));
        DryRunResult repeated = dryRunService.run(original.traceId(), new DryRunRequest("integration-test-repeat", "mock", true));

        assertThat(result.status()).as(result + " snapshots=" + originalSnapshots.stream()
                .map(snapshot -> snapshot.getSnapshotType() + "=" + snapshot.getPayloadJson()).toList()).isEqualTo("SUCCEEDED");
        assertThat(result.dryRunTraceId()).isNotBlank();
        assertThat(result.diffId()).isNotNull();
        assertThat(traceDiffService.get(original.traceId(), result.dryRunTraceId()).riskLevel())
                .isNotEqualTo("HIGH");
        assertThat(repeated.status()).isEqualTo("SUCCEEDED");
        assertThat(finalOutput(result.dryRunTraceId())).isEqualTo(finalOutput(repeated.dryRunTraceId()));
        assertThat(traceQueryService.get(result.dryRunTraceId()).run().getDryRun()).isTrue();
        assertThat(counts()).isEqualTo(before);
        verifyNoInteractions(orderAdapter, deepSeekAgentProvider, rabbitTemplate);
    }

    private String finalOutput(String traceId) {
        return traceQueryService.get(traceId).snapshots().stream()
                .filter(snapshot -> "FINAL_OUTPUT".equals(snapshot.getSnapshotType()))
                .reduce((left, right) -> right).orElseThrow().getPayloadJson();
    }

    private Map<String, Long> counts() {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String table : BUSINESS_TABLES) {
            values.put(table, jdbcTemplate.queryForObject("select count(*) from " + table, Long.class));
        }
        return values;
    }
}
