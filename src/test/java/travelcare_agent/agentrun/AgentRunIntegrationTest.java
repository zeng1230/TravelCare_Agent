package travelcare_agent.agentrun;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.agentrun.entity.AgentRun;
import travelcare_agent.agentrun.repository.AgentRunRepository;
import travelcare_agent.conversation.service.SessionService;
import travelcare_agent.workflow.WorkflowTaskWorker;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(agentRunRepository.findBySessionId(sessionId, 1, 20))
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
    }

    @Test
    void asynchronousWorkerWritesSucceededAgentRunAndPayloadKeepsUserEventId() {
        Long sessionId = sessionService.createSession(1001L, "WEB").sessionId();

        SessionService.SendMessageResult accepted = sessionService.sendMessage(
                sessionId,
                "Can I refund order ORD-1001?",
                "stage4-async-run",
                true
        );

        assertThat(accepted.taskId()).isNotNull();
        assertThat(workflowTaskRepository.findById(accepted.taskId()))
                .get()
                .satisfies(task -> assertThat(task.getPayloadJson())
                        .contains("\"userEventId\":" + accepted.userEventId()));

        workflowTaskWorker.processTask(Map.of("taskId", accepted.taskId()));

        assertThat(agentRunRepository.findBySessionId(sessionId, 1, 20))
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
    }
}
