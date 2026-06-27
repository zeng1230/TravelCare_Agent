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
    private AuditService auditService;
    private travelcare_agent.agent.ContextAssembler contextAssembler;
    private travelcare_agent.agentrun.service.AgentRunService agentRunService;
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
        auditService = mock(AuditService.class);
        travelcare_agent.human.service.HumanReviewService humanReviewService = mock(travelcare_agent.human.service.HumanReviewService.class);
        travelcare_agent.refund.repository.RefundCaseRepository refundCaseRepository = mock(travelcare_agent.refund.repository.RefundCaseRepository.class);
        contextAssembler = mock(travelcare_agent.agent.ContextAssembler.class);
        agentRunService = mock(travelcare_agent.agentrun.service.AgentRunService.class);
        travelcare_agent.agentrun.entity.AgentRun agentRun = new travelcare_agent.agentrun.entity.AgentRun();
        agentRun.setId(9001L);
        agentRun.setSessionId(100L);
        agentRun.setWorkflowId(10L);
        agentRun.setTaskId(1L);
        agentRun.setStatus("RUNNING");
        when(agentRunService.startRun(anyLong(), anyLong(), anyLong(), any(), anyString(), anyString(), anyString()))
                .thenReturn(agentRun);

        worker = new WorkflowTaskWorker(
                taskRepository, taskService, lockService, workflowEngine, sessionRepository,
                eventService, intentClassifier, responseGenerator, objectMapper, auditService,
                humanReviewService, refundCaseRepository, contextAssembler, agentRunService
        );

        travelcare_agent.conversation.entity.SessionEvent event = travelcare_agent.conversation.entity.SessionEvent.create(
                100L,
                1,
                travelcare_agent.enums.SessionEventType.MESSAGE,
                travelcare_agent.enums.SessionEventRole.USER,
                "hello",
                null
        );
        event.setId(301L);
        travelcare_agent.retrieval.service.RetrievalSnippet snippet = new travelcare_agent.retrieval.service.RetrievalSnippet(
                201L,
                202L,
                "Refund SOP",
                "Policy text",
                "https://example.com/refund",
                1.0
        );
        travelcare_agent.memory.entity.AgentMemory memory = new travelcare_agent.memory.entity.AgentMemory();
        memory.setId(401L);

        travelcare_agent.agent.AgentContext context = new travelcare_agent.agent.AgentContext(
                java.util.List.of(event),
                null,
                null,
                java.util.List.of(snippet),
                java.util.List.of(memory)
        );
        when(contextAssembler.assemble(anyLong(), anyString())).thenReturn(context);
        travelcare_agent.conversation.entity.SessionEvent assistantEvent = travelcare_agent.conversation.entity.SessionEvent.create(
                100L,
                2,
                travelcare_agent.enums.SessionEventType.MESSAGE,
                travelcare_agent.enums.SessionEventRole.ASSISTANT,
                "answer",
                "{}"
        );
        assistantEvent.setId(302L);
        when(eventService.appendMessage(anyLong(), eq(travelcare_agent.enums.SessionEventRole.ASSISTANT), anyString(), anyString()))
                .thenReturn(assistantEvent);

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
        verify(auditService).recordKnowledgeRetrieved(eq(100L), eq(10L), eq(java.util.List.of(201L)), eq(java.util.List.of(202L)));
        verify(auditService).recordMemoryRead(eq(100L), eq(10L), eq(java.util.List.of(401L)));
        verify(auditService).recordContextAssembled(eq(100L), eq(10L), eq(java.util.List.of(201L)), eq(java.util.List.of(202L)), eq(java.util.List.of(401L)), eq(java.util.List.of(301L)));
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

        verify(taskService).handleWorkerFailure(eq(1L), eq("LOCK_CONFLICT"), anyString(), any(), any());
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

        verify(taskService).handleWorkerFailure(eq(1L), eq("SYSTEM_ERROR"), eq("DB down"), any(), any());
    }

    @Test
    void processTask_ShouldMarkAgentRunFailed_WhenWorkflowResumeFails() {
        WorkflowTask task = new WorkflowTask();
        task.setId(1L);
        task.setWorkflowId(10L);
        task.setSessionId(100L);
        task.setStatus(WorkflowTaskStatus.PENDING);
        task.setTaskType("order_refund_inquiry");
        task.setPayloadJson("{\"message\":\"hello\",\"userEventId\":301}");

        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        Session session = Session.create(1001L, "WEB");
        session.setId(100L);
        when(sessionRepository.findById(100L)).thenReturn(Optional.of(session));
        when(workflowEngine.resume(eq(10L), eq("order_refund_inquiry"), any()))
                .thenThrow(new RuntimeException("workflow resume failed"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", 1L);

        worker.processTask(payload);

        verify(agentRunService).markFailed(
                eq(9001L),
                eq("FAILED_GENERATION"),
                eq("WORKFLOW_RESUME_FAILED"),
                any(RuntimeException.class)
        );
        verify(taskService).handleWorkerFailure(eq(1L), eq("SYSTEM_ERROR"), eq("workflow resume failed"), any(), any());
    }
}
