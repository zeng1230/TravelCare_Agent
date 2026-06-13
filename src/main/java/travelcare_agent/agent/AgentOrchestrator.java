package travelcare_agent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.human.service.HumanReviewService;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.workflow.WorkflowEngine;
import travelcare_agent.workflow.workflows.OrderRefundInquiryWorkflow;
import travelcare_agent.retrieval.service.RetrievalSnippet;
import travelcare_agent.memory.entity.AgentMemory;
import java.util.List;
import java.util.Map;

@Service
public class AgentOrchestrator {

    private final MockIntentClassifier intentClassifier;
    private final MockResponseGenerator responseGenerator;
    private final WorkflowEngine workflowEngine;
    private final HumanReviewService humanReviewService;
    private final RefundCaseRepository refundCaseRepository;
    private final ObjectMapper objectMapper;
    private final ContextAssembler contextAssembler;
    private final AgentModelService agentModelService;

    @Autowired
    public AgentOrchestrator(
            MockIntentClassifier intentClassifier,
            MockResponseGenerator responseGenerator,
            WorkflowEngine workflowEngine,
            HumanReviewService humanReviewService,
            RefundCaseRepository refundCaseRepository,
            ObjectMapper objectMapper,
            ContextAssembler contextAssembler,
            AgentModelService agentModelService
    ) {
        this.intentClassifier = intentClassifier;
        this.responseGenerator = responseGenerator;
        this.workflowEngine = workflowEngine;
        this.humanReviewService = humanReviewService;
        this.refundCaseRepository = refundCaseRepository;
        this.objectMapper = objectMapper;
        this.contextAssembler = contextAssembler;
        this.agentModelService = agentModelService;
    }

    public AgentOrchestrator(
            MockIntentClassifier intentClassifier,
            MockResponseGenerator responseGenerator,
            WorkflowEngine workflowEngine,
            HumanReviewService humanReviewService,
            RefundCaseRepository refundCaseRepository,
            ObjectMapper objectMapper,
            ContextAssembler contextAssembler
    ) {
        this(intentClassifier, responseGenerator, workflowEngine, humanReviewService, refundCaseRepository,
                objectMapper, contextAssembler, null);
    }

    public AgentReply handle(AgentRequest request) {
        AgentContext agentContext;
        try {
            agentContext = contextAssembler.assemble(request.sessionId(), request.message());
        } catch (RuntimeException ex) {
            throw new AgentStageException("FAILED_CONTEXT", "CONTEXT_ASSEMBLY_FAILED", null, null, ex);
        }

        List<Long> contextEventIds = agentContext.recentEvents().stream()
                .map(SessionEvent::getId)
                .toList();
        MockIntentClassifier.IntentResult intent = agentModelService == null
                ? intentClassifier.classify(request.message())
                : agentModelService.classifyIntentAndExtractSlots(
                        request.sessionId(), null, contextEventIds, request.message()
                );
        WorkflowEngine.WorkflowResult workflowResult;
        try {
            workflowResult = workflowEngine.start(
                    OrderRefundInquiryWorkflow.TYPE,
                    new WorkflowEngine.WorkflowCommand(
                            request.sessionId(),
                            request.userId(),
                            null,
                            intent.orderNo(),
                            request.message()
                    )
            );
        } catch (RuntimeException ex) {
            throw new AgentStageException("FAILED_GENERATION", "WORKFLOW_EXECUTION_FAILED", agentContext, null, ex);
        }

        if (workflowResult.workflow().getStatus() == WorkflowStatus.NEED_HUMAN) {
            // Try to resolve refundCaseId
            Long refundCaseId = refundCaseRepository.findByWorkflowId(workflowResult.workflow().getId())
                    .map(RefundCase::getId).orElse(null);

            // Extract reasonCode from workflow state if present
            String reasonCode = "NEED_HUMAN";
            try {
                Map<String, String> state = objectMapper.readValue(workflowResult.workflow().getStateJson(), new TypeReference<>() {});
                if (state != null && state.containsKey("reasonCode")) {
                    reasonCode = state.get("reasonCode");
                }
            } catch (Exception ignore) {}

            humanReviewService.createCase(
                    request.sessionId(),
                    workflowResult.workflow().getId(),
                    refundCaseId,
                    "REFUND_REVIEW",
                    "HIGH",
                    reasonCode,
                    workflowResult.workflow().getStateJson()
            );
        }

        String answer;
        try {
            String deterministicAnswer = responseGenerator.generate(intent, workflowResult, agentContext);
            answer = agentModelService == null
                    ? deterministicAnswer
                    : agentModelService.generateCustomerAnswer(
                            request.sessionId(), workflowResult.workflow().getId(), contextEventIds, deterministicAnswer
                    );
        } catch (RuntimeException ex) {
            throw new AgentStageException("FAILED_GENERATION", "RESPONSE_GENERATION_FAILED", agentContext, workflowResult.workflow().getId(), ex);
        }

        List<Long> retrievalChunkIds = agentContext.policySnippets().stream()
                .map(RetrievalSnippet::chunkId)
                .toList();
        List<Long> documentIds = agentContext.policySnippets().stream()
                .map(RetrievalSnippet::documentId)
                .distinct()
                .toList();
        List<Long> memoryIds = agentContext.activeMemories().stream()
                .map(AgentMemory::getId)
                .toList();
        List<Long> eventIds = agentContext.recentEvents().stream()
                .map(SessionEvent::getId)
                .toList();

        return new AgentReply(
                intent.intent(),
                intent.orderNo(),
                workflowResult.workflow().getId(),
                workflowResult.workflow().getStatus().name(),
                answer,
                documentIds,
                retrievalChunkIds,
                memoryIds,
                eventIds
        );
    }

    public record AgentRequest(Long sessionId, Long userId, String message) {
    }

    public record AgentReply(
            String intent,
            String orderNo,
            Long workflowId,
            String workflowStatus,
            String answer,
            List<Long> documentIds,
            List<Long> retrievalChunkIds,
            List<Long> memoryIds,
            List<Long> eventIds
    ) {
    }

    public static class AgentStageException extends RuntimeException {
        private final String agentRunStatus;
        private final String errorCode;
        private final AgentContext agentContext;
        private final Long workflowId;

        public AgentStageException(
                String agentRunStatus,
                String errorCode,
                AgentContext agentContext,
                Long workflowId,
                Throwable cause
        ) {
            super(cause.getMessage(), cause);
            this.agentRunStatus = agentRunStatus;
            this.errorCode = errorCode;
            this.agentContext = agentContext;
            this.workflowId = workflowId;
        }

        public String agentRunStatus() {
            return agentRunStatus;
        }

        public String errorCode() {
            return errorCode;
        }

        public AgentContext agentContext() {
            return agentContext;
        }

        public Long workflowId() {
            return workflowId;
        }
    }
}
