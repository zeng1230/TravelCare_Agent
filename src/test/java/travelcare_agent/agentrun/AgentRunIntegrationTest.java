package travelcare_agent.agentrun;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import travelcare_agent.agentrun.entity.AgentRun;
import travelcare_agent.agentrun.repository.AgentRunRepository;
import travelcare_agent.conversation.service.SessionService;
import travelcare_agent.workflow.WorkflowTaskWorker;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import travelcare_agent.trace.TraceQueryService;

@SpringBootTest
@Transactional
class AgentRunIntegrationTest {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private travelcare_agent.workflow.repository.WorkflowTaskRepository workflowTaskRepository;

    @Autowired
    private WorkflowTaskWorker workflowTaskWorker;
    @Autowired private TraceQueryService traceQueryService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void stage10bMigrationRemovesPayloadColumnsAndAllowsNullSession() {
        String sessionNullable = jdbcTemplate.queryForObject("""
                SELECT IS_NULLABLE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'agent_runs'
                  AND COLUMN_NAME = 'session_id'
                """, String.class);
        Integer removedPayloadColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'agent_runs'
                  AND COLUMN_NAME IN ('request_json', 'response_json')
                """, Integer.class);
        Integer trackingColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'agent_runs'
                  AND COLUMN_NAME IN ('provider_mode', 'fallback_provider', 'fallback_model',
                                      'request_hash', 'response_hash', 'total_tokens', 'fallback_used')
                """, Integer.class);

        assertThat(sessionNullable).isEqualTo("YES");
        assertThat(removedPayloadColumns).isZero();
        assertThat(trackingColumns).isEqualTo(7);
    }

    @Test
    void synchronousMessageWritesSucceededAgentRun() {
        Long sessionId = sessionService.createSession(1001L, "WEB").sessionId();

        SessionService.SendMessageResult result = sessionService.sendMessage(
                sessionId,
                "Can I refund order ORD-1001?",
                "stage4-sync-run",
                false
        );

        assertThat(result.assistantEventId()).isNotNull();
        assertThat(result.traceAvailable()).isTrue();
        assertThat(result.traceId()).isNotBlank();
        TraceQueryService.TraceDiagnostics diagnostics = traceQueryService.diagnostics(result.traceId());
        assertThat(diagnostics.provider()).isEqualTo("mock");
        assertThat(diagnostics.model()).isNotBlank();
        assertThat(diagnostics.promptVersion()).isNotBlank();
        assertThat(diagnostics.finalOutput()).isNotNull();
        assertThat(diagnostics.workflowPath()).extracting(travelcare_agent.trace.entity.TraceSpan::getSpanType)
                .contains("WORKFLOW", "WORKFLOW_STEP");
        assertThat(diagnostics.toolCalls()).singleElement().satisfies(span -> {
            assertThat(span.getStatus()).isEqualTo("SUCCEEDED");
            assertThat(span.getDurationMs()).isGreaterThanOrEqualTo(0L);
        });
        assertThat(diagnostics.policyDecisions()).isNotEmpty();
        assertThat(traceQueryService.get(result.traceId()).spans())
                .extracting(travelcare_agent.trace.entity.TraceSpan::getSpanType)
                .contains("REQUEST", "CONTEXT", "RETRIEVAL", "MODEL", "WORKFLOW", "WORKFLOW_STEP", "TOOL", "POLICY", "OUTPUT", "AUDIT");
        assertThat(traceQueryService.get(result.traceId()).snapshots())
                .extracting(travelcare_agent.trace.entity.TraceSnapshot::getSnapshotType)
                .contains(
                        "USER_INPUT",
                        "CONTEXT_SUMMARY",
                        "RETRIEVAL_SUMMARY",
                        "MODEL_INPUT",
                        "MODEL_OUTPUT",
                        "TOOL_REQUEST",
                        "TOOL_RESULT",
                        "POLICY_INPUT",
                        "POLICY_DECISION",
                        "WORKFLOW_PATH",
                        "FINAL_OUTPUT"
                );
        assertThat(agentRunRepository.findBySessionId(sessionId, 1, 20))
                .filteredOn(run -> "SYNC_REPLY".equals(run.getRunType()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.getStatus()).isEqualTo("SUCCEEDED");
                    assertThat(run.getRunType()).isEqualTo("SYNC_REPLY");
                    assertThat(run.getSource()).isEqualTo("session_service");
                    assertThat(run.getInputEventIdsJson()).contains(String.valueOf(result.userEventId()));
                    assertThat(run.getRetrievalChunkIdsJson()).startsWith("[");
                    assertThat(run.getMemoryIdsJson()).startsWith("[");
                    assertThat(run.getWorkflowSnapshotJson()).contains("RESPONDED");
                    assertThat(run.getContextHash()).hasSize(64);
                    assertThat(run.getAnswerHash()).hasSize(64);
                    assertThat(run.getOutputEventId()).isEqualTo(result.assistantEventId());
                    assertThat(run.getLatencyMs()).isGreaterThanOrEqualTo(0L);
                });
        assertThat(agentRunRepository.findBySessionId(sessionId, 1, 20))
                .extracting(AgentRun::getRunType)
                .contains("INTENT_CLASSIFICATION", "RESPONSE_GENERATION");
        assertThat(agentRunRepository.findBySessionId(sessionId, 1, 20))
                .filteredOn(run -> "agent_model_service".equals(run.getSource()))
                .hasSize(2)
                .allSatisfy(run -> {
                    assertThat(run.getStatus()).isEqualTo("SUCCESS");
                    assertThat(run.getProviderMode()).isEqualTo("mock");
                    assertThat(run.getPromptVersion()).isIn("intent-classifier-v1", "response-generator-v1");
                    assertThat(run.getRequestHash()).hasSize(64);
                    assertThat(run.getResponseHash()).hasSize(64);
                    assertThat(run.getFallbackUsed()).isFalse();
                });
    }

    @Test
    void asynchronousWorkerWritesSucceededAgentRunAndPayloadKeepsUserEventId() throws Exception {
        Long sessionId = sessionService.createSession(1001L, "WEB").sessionId();

        SessionService.SendMessageResult accepted = sessionService.sendMessage(
                sessionId,
                "Can I refund order ORD-1001?",
                "stage4-async-run",
                true
        );

        assertThat(accepted.taskId()).isNotNull();
        assertThat(accepted.traceAvailable()).isTrue();
        assertThat(workflowTaskRepository.findById(accepted.taskId()))
                .get()
                .satisfies(task -> assertThat(task.getPayloadJson())
                        .contains("\"userEventId\":" + accepted.userEventId()));

        String payloadJson = workflowTaskRepository.findById(accepted.taskId()).orElseThrow().getPayloadJson();
        assertThat(payloadJson).contains(accepted.traceId(), "parentSpanId");
        String parentSpanId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadJson).path("parentSpanId").asText();
        workflowTaskWorker.processTask(Map.of("taskId", accepted.taskId(), "traceId", accepted.traceId(), "parentSpanId", parentSpanId));

        assertThat(agentRunRepository.findBySessionId(sessionId, 1, 20))
                .filteredOn(run -> "ASYNC_WORKER_REPLY".equals(run.getRunType()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.getStatus()).isEqualTo("SUCCEEDED");
                    assertThat(run.getRunType()).isEqualTo("ASYNC_WORKER_REPLY");
                    assertThat(run.getSource()).isEqualTo("workflow_task_worker");
                    assertThat(run.getTaskId()).isEqualTo(accepted.taskId());
                    assertThat(run.getInputEventIdsJson()).contains(String.valueOf(accepted.userEventId()));
                    assertThat(run.getWorkflowId()).isEqualTo(accepted.workflowId());
                    assertThat(run.getOutputEventId()).isNotNull();
                    assertThat(run.getContextHash()).hasSize(64);
                    assertThat(run.getAnswerHash()).hasSize(64);
                });
        assertThat(agentRunRepository.findBySessionId(sessionId, 1, 20))
                .extracting(AgentRun::getRunType)
                .contains("INTENT_CLASSIFICATION", "RESPONSE_GENERATION");
        assertThat(traceQueryService.get(accepted.traceId()).spans())
                .extracting(travelcare_agent.trace.entity.TraceSpan::getSpanType)
                .contains("ASYNC_TASK", "WORKFLOW", "OUTPUT");
    }
}
