package travelcare_agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import travelcare_agent.agent.MockIntentClassifier;
import travelcare_agent.agent.MockResponseGenerator;
import travelcare_agent.audit.AuditService;
import travelcare_agent.common.lock.LockService;
import travelcare_agent.conversation.entity.Session;
import travelcare_agent.conversation.repository.SessionRepository;
import travelcare_agent.conversation.service.SessionEventService;
import travelcare_agent.enums.WorkflowTaskStatus;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WorkflowTaskWorkerTest {

    private WorkflowTaskRepository taskRepository;
    private WorkflowTaskService taskService;
    private LockService lockService;
    private WorkflowEngine workflowEngine;
    private SessionRepository sessionRepository;
    private SessionEventService eventService;
    private travelcare_agent.agent.ContextAssembler contextAssembler;
    private WorkflowTaskWorker worker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskRepository = mock(WorkflowTaskRepository.class);
        taskService = mock(WorkflowTaskService.class);
        lockService = mock(LockService.class);
        workflowEngine = mock(WorkflowEngine.class);
        sessionRepository = mock(SessionRepository.class);
        eventService = mock(SessionEventService.class);
        MockIntentClassifier intentClassifier = new MockIntentClassifier();
        MockResponseGenerator responseGenerator = new MockResponseGenerator();
        ObjectMapper objectMapper = new ObjectMapper();
        AuditService auditService = mock(AuditService.class);
        travelcare_agent.human.service.HumanReviewService humanReviewService = mock(travelcare_agent.human.service.HumanReviewService.class);
        travelcare_agent.refund.repository.RefundCaseRepository refundCaseRepository = mock(travelcare_agent.refund.repository.RefundCaseRepository.class);
        contextAssembler = mock(travelcare_agent.agent.ContextAssembler.class);

        worker = new WorkflowTaskWorker(
                taskRepository, taskService, lockService, workflowEngine, sessionRepository,
                eventService, intentClassifier, responseGenerator, objectMapper, auditService,
                humanReviewService, refundCaseRepository, contextAssembler
        );

        travelcare_agent.agent.AgentContext context = new travelcare_agent.agent.AgentContext(
                java.util.List.of(),
                null,
                null,
                java.util.List.of(),
                java.util.List.of()
        );
        when(contextAssembler.assemble(anyLong(), anyString())).thenReturn(context);

        when(lockService.withLock(anyString(), anyLong(), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(2);
                    return supplier.get();
                });
    }


    @Test
    void processTask_ShouldExecuteWorkflow_WhenTaskIsPending() {
        WorkflowTask task = new WorkflowTask();
        task.setId(1L);
        task.setWorkflowId(10L);
        task.setSessionId(100L);
        task.setStatus(WorkflowTaskStatus.PENDING);
        task.setTaskType("order_refund_inquiry");
        task.setPayloadJson("{\"message\":\"hello\"}");

        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        Session session = Session.create(1001L, "WEB");
        session.setId(100L);
        when(sessionRepository.findById(100L)).thenReturn(Optional.of(session));

        WorkflowEngine.WorkflowResult result = new WorkflowEngine.WorkflowResult(
                travelcare_agent.workflow.entity.Workflow.create(100L, "order_refund_inquiry"),
                "answer"
        );
        result.workflow().setId(10L);
        result.workflow().setStatus(travelcare_agent.enums.WorkflowStatus.RESPONDED);
        when(workflowEngine.resume(eq(10L), eq("order_refund_inquiry"), any())).thenReturn(result);

        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", 1L);

        worker.processTask(payload);

        verify(taskService).updateStatus(1L, WorkflowTaskStatus.RUNNING);
        verify(workflowEngine).resume(eq(10L), eq("order_refund_inquiry"), any());
        verify(taskService).markTerminalState(1L, WorkflowTaskStatus.SUCCEEDED);
        verify(eventService).appendWorkflowRequested(eq(100L), anyString());
        verify(eventService).appendMessage(eq(100L), eq(travelcare_agent.enums.SessionEventRole.ASSISTANT), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void processTask_ShouldIncrementRetry_WhenLockConflict() {
        WorkflowTask task = new WorkflowTask();
        task.setId(1L);
        task.setWorkflowId(10L);
        task.setStatus(WorkflowTaskStatus.PENDING);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        when(lockService.withLock(anyString(), anyLong(), any(Supplier.class)))
                .thenThrow(new IllegalStateException("Failed to acquire lock"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", 1L);

        worker.processTask(payload);

        verify(taskService).incrementRetry(eq(1L), eq("LOCK_CONFLICT"), anyString(), any());
        verify(workflowEngine, never()).resume(anyLong(), anyString(), any());
    }

    @Test
    void processTask_ShouldIncrementRetry_WhenSystemError() {
        WorkflowTask task = new WorkflowTask();
        task.setId(1L);
        task.setWorkflowId(10L);
        task.setSessionId(100L);
        task.setStatus(WorkflowTaskStatus.PENDING);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        when(sessionRepository.findById(any())).thenThrow(new RuntimeException("DB down"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", 1L);

        worker.processTask(payload);

        verify(taskService).incrementRetry(eq(1L), eq("SYSTEM_ERROR"), eq("DB down"), any());
    }
}
