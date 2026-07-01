package travelcare_agent.conversation;

import travelcare_agent.adapter.order.OrderSnapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import travelcare_agent.adapter.order.MockOrderAdapter;
import travelcare_agent.agent.AgentOrchestrator;
import travelcare_agent.agent.MockIntentClassifier;
import travelcare_agent.agent.MockResponseGenerator;
import travelcare_agent.api.SessionController;
import travelcare_agent.common.exception.GlobalExceptionHandler;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.conversation.repository.InMemorySessionEventRepository;
import travelcare_agent.conversation.repository.InMemorySessionRepository;
import travelcare_agent.conversation.service.SessionEventService;
import travelcare_agent.conversation.service.SessionService;
import travelcare_agent.enums.OrderStatus;
import travelcare_agent.enums.SessionStatus;
import travelcare_agent.tool.IdempotencyService;
import travelcare_agent.tool.ToolService;
import travelcare_agent.tool.entity.IdempotencyKey;
import travelcare_agent.tool.entity.ToolCall;
import travelcare_agent.tool.repository.IdempotencyKeyRepository;
import travelcare_agent.tool.repository.ToolCallRepository;
import travelcare_agent.workflow.WorkflowEngine;
import travelcare_agent.workflow.WorkflowRegistry;
import travelcare_agent.workflow.repository.InMemoryWorkflowRepository;
import travelcare_agent.workflow.repository.InMemoryWorkflowStepRepository;
import travelcare_agent.workflow.workflows.OrderRefundInquiryWorkflow;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionControllerTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final InMemorySessionRepository sessionRepository = new InMemorySessionRepository();
    private final InMemorySessionEventRepository eventRepository = new InMemorySessionEventRepository();
    private final SessionEventService eventService = new SessionEventService(eventRepository);
    
    private final IdempotencyKeyRepository keyRepo = new IdempotencyKeyRepository() {
        private final java.util.Map<String, IdempotencyKey> store = new java.util.concurrent.ConcurrentHashMap<>();
        public IdempotencyKey save(IdempotencyKey key) { store.put(key.getIdempotencyKey(), key); return key; }
        public Optional<IdempotencyKey> findByKey(String key) { return Optional.ofNullable(store.get(key)); }
    };
    private final IdempotencyService idempotencyService = new IdempotencyService(keyRepo);
    
    private final travelcare_agent.workflow.repository.InMemoryWorkflowRepository workflowRepository = new travelcare_agent.workflow.repository.InMemoryWorkflowRepository();
    private final travelcare_agent.workflow.WorkflowTaskService workflowTaskService = org.mockito.Mockito.mock(travelcare_agent.workflow.WorkflowTaskService.class);
    private static final travelcare_agent.agent.ContextAssembler contextAssembler = org.mockito.Mockito.mock(travelcare_agent.agent.ContextAssembler.class);
    private final travelcare_agent.audit.AuditService auditService = org.mockito.Mockito.mock(travelcare_agent.audit.AuditService.class);

    static {
        travelcare_agent.agent.AgentContext dummyContext = new travelcare_agent.agent.AgentContext(
                List.of(),
                null,
                null,
                List.of(),
                List.of()
        );
        org.mockito.Mockito.when(contextAssembler.assemble(org.mockito.Mockito.anyLong(), org.mockito.Mockito.anyString()))
                .thenReturn(dummyContext);
    }

    private final travelcare_agent.workflow.repository.WorkflowTaskRepository workflowTaskRepository = org.mockito.Mockito.mock(travelcare_agent.workflow.repository.WorkflowTaskRepository.class);
    private final travelcare_agent.agentrun.service.AgentRunService agentRunService = org.mockito.Mockito.mock(travelcare_agent.agentrun.service.AgentRunService.class);

    private final SessionService sessionService = new SessionService(
            sessionRepository, eventService, orchestrator(idempotencyService), idempotencyService, workflowRepository, workflowTaskService, workflowTaskRepository, objectMapper, auditService, agentRunService
    );
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SessionController(sessionService, eventService, contextAssembler))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void createSessionPersistsActiveSession() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSessionBody(1001L, "WEB"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.sessionId", notNullValue()))
                .andExpect(jsonPath("$.data.status").value(SessionStatus.ACTIVE.name()));
    }

    @Test
    void sendMessagePersistsUserWorkflowAndAssistantEventsInOrder() throws Exception {
        long sessionId = sessionService.createSession(1001L, "WEB").sessionId();

        mockMvc.perform(post("/api/sessions/{sessionId}/messages", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageBody("Can I refund order ORD-10?", "msg-001", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(containsString("Order ORD-10 is eligible for refund inquiry")))
                .andExpect(jsonPath("$.data.answer").value(containsString("Rule: paid, refundable orders departing after 24 hours can be reviewed.")))
                .andExpect(jsonPath("$.data.userEventId", notNullValue()))
                .andExpect(jsonPath("$.data.workflowEventId", notNullValue()))
                .andExpect(jsonPath("$.data.assistantEventId", notNullValue()));

        mockMvc.perform(get("/api/sessions/{sessionId}/events", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events[*].seqNo", contains(1, 2, 3)))
                .andExpect(jsonPath("$.data.events[*].eventType", contains("MESSAGE", "WORKFLOW", "MESSAGE")))
                .andExpect(jsonPath("$.data.events[*].role", contains("USER", "SYSTEM", "ASSISTANT")))
                .andExpect(jsonPath("$.data.events[0].content").value("Can I refund order ORD-10?"))
                .andExpect(jsonPath("$.data.events[2].content").value(containsString("Order ORD-10 is eligible for refund inquiry")));
    }

    @Test
    void sendMessagePersistsCitationMetadata() throws Exception {
        long sessionId = sessionService.createSession(1001L, "WEB").sessionId();

        travelcare_agent.retrieval.service.RetrievalSnippet snippet = new travelcare_agent.retrieval.service.RetrievalSnippet(10L, 501L, "Snippet SOP", "policy context", "http://uri", 1.0);
        travelcare_agent.memory.entity.AgentMemory memory = new travelcare_agent.memory.entity.AgentMemory();
        memory.setId(901L);
        memory.setMemoryValue("val");
        
        travelcare_agent.agent.AgentContext mockContext = new travelcare_agent.agent.AgentContext(
                List.of(),
                null,
                null,
                List.of(snippet),
                List.of(memory),
                new travelcare_agent.answerability.AnswerabilityDecision(
                        travelcare_agent.answerability.AnswerabilityStatus.ANSWERABLE,
                        travelcare_agent.answerability.AnswerabilityReasonCode.SUFFICIENT_CONTEXT,
                        travelcare_agent.answerability.AnswerabilityRequiredAction.ALLOW_MODEL,
                        travelcare_agent.answerability.CitationPolicy.REQUIRED,
                        List.of(501L), false, false, false,
                        List.of(new travelcare_agent.answerability.CitationMetadata(
                                "run-501", 501L, 10L, "Snippet SOP", "http://uri", null, null)),
                        List.of(), null)
        );
        org.mockito.Mockito.when(contextAssembler.assemble(org.mockito.Mockito.eq(sessionId), org.mockito.Mockito.anyString()))
                .thenReturn(mockContext);

        mockMvc.perform(post("/api/sessions/{sessionId}/messages", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageBody("Can I refund order ORD-10?", "msg-002", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistantEventId", notNullValue()));

        mockMvc.perform(get("/api/sessions/{sessionId}/events", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events[2].metadataJson", containsString("retrievalChunkIds")))
                .andExpect(jsonPath("$.data.events[2].metadataJson", containsString("501")))
                .andExpect(jsonPath("$.data.events[2].metadataJson", containsString("memoryIds")))
                .andExpect(jsonPath("$.data.events[2].metadataJson", containsString("901")))
                .andExpect(jsonPath("$.data.events[2].metadataJson", containsString("\"businessDecisionLocked\":true")))
                .andExpect(jsonPath("$.data.events[2].metadataJson", containsString("\"ragMayOverrideBusinessDecision\":false")));
    }

    @Test
    void queryMissingSessionEventsReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/sessions/{sessionId}/events", 9999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.code()));
    }

    record CreateSessionBody(Long userId, String channel) {
    }

    record SendMessageBody(String content, String idempotencyKey, Boolean async) {
    }

    private static AgentOrchestrator orchestrator(IdempotencyService idempotencyService) {
        OrderSnapshot order = new OrderSnapshot(
                10L,
                "ORD-10",
                1001L,
                OrderStatus.PAID,
                true,
                new BigDecimal("399.00"),
                LocalDateTime.now().plusHours(48)
        );
        
        ToolCallRepository toolCallRepository = new ToolCallRepository() {
            public ToolCall save(ToolCall call) {
                if (call.getId() == null) call.setId(new java.util.Random().nextLong());
                return call;
            }
            public Optional<ToolCall> findById(Long id) { return Optional.empty(); }
            public Optional<ToolCall> findByIdempotencyKey(String key) { return Optional.empty(); }
        };
        ToolService toolService = new ToolService(toolCallRepository, idempotencyService);

        travelcare_agent.refund.repository.InMemoryRefundCaseRepository refundRepo = new travelcare_agent.refund.repository.InMemoryRefundCaseRepository();
        travelcare_agent.human.repository.InMemoryHumanReviewCaseRepository hrRepo = new travelcare_agent.human.repository.InMemoryHumanReviewCaseRepository();
        travelcare_agent.audit.AuditService auditService = org.mockito.Mockito.mock(travelcare_agent.audit.AuditService.class);
        travelcare_agent.conversation.service.SessionEventService eventService = org.mockito.Mockito.mock(travelcare_agent.conversation.service.SessionEventService.class);
        travelcare_agent.workflow.repository.InMemoryWorkflowRepository workflowRepo = new travelcare_agent.workflow.repository.InMemoryWorkflowRepository();

        travelcare_agent.human.service.HumanReviewService humanReviewService = new travelcare_agent.human.service.HumanReviewService(
                hrRepo, eventService, auditService, workflowRepo, refundRepo
        );

        WorkflowEngine engine = new WorkflowEngine(
                workflowRepo,
                new InMemoryWorkflowStepRepository(),
                new WorkflowRegistry(List.of(new OrderRefundInquiryWorkflow(
                        new MockOrderAdapter((orderId, orderNo, userId) -> Optional.of(order)
                                .filter(snapshot -> orderId == null || snapshot.orderId().equals(orderId))
                                .filter(snapshot -> orderNo == null || snapshot.orderNo().equals(orderNo))
                                .filter(snapshot -> snapshot.userId().equals(userId))),
                        toolService,
                        java.time.Clock.systemDefaultZone(),
                        new travelcare_agent.policy.RefundEligibilityPolicy(),
                        refundRepo,
                        auditService
                )))
        );
        return new AgentOrchestrator(
                new MockIntentClassifier(),
                new MockResponseGenerator(),
                engine,
                humanReviewService,
                refundRepo,
                objectMapper,
                contextAssembler
        );
    }
}
