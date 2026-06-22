package travelcare_agent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.answerability.AnswerabilityDecision;
import travelcare_agent.answerability.AnswerabilityRequiredAction;
import travelcare_agent.human.service.HumanReviewService;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.workflow.WorkflowEngine;
import travelcare_agent.workflow.workflows.OrderRefundInquiryWorkflow;
import travelcare_agent.retrieval.service.RetrievalSnippet;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.trace.TraceService;
import travelcare_agent.trace.TraceSnapshotType;
import travelcare_agent.agent.safety.ModelSafetyContext;
import travelcare_agent.agent.safety.ModelSafetyDecision;
import travelcare_agent.agent.safety.ModelSafetyDecisionType;
import travelcare_agent.agent.safety.SafeModelResult;
import travelcare_agent.answerability.CitationPolicy;
import java.time.LocalDateTime;
import java.util.Set;
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
    private final TraceService traceService;

    @Autowired
    public AgentOrchestrator(
            MockIntentClassifier intentClassifier,
            MockResponseGenerator responseGenerator,
            WorkflowEngine workflowEngine,
            HumanReviewService humanReviewService,
            RefundCaseRepository refundCaseRepository,
            ObjectMapper objectMapper,
            ContextAssembler contextAssembler,
            AgentModelService agentModelService,
            TraceService traceService
    ) {
        this.intentClassifier = intentClassifier;
        this.responseGenerator = responseGenerator;
        this.workflowEngine = workflowEngine;
        this.humanReviewService = humanReviewService;
        this.refundCaseRepository = refundCaseRepository;
        this.objectMapper = objectMapper;
        this.contextAssembler = contextAssembler;
        this.agentModelService = agentModelService;
        this.traceService = traceService;
    }

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
        this(intentClassifier, responseGenerator, workflowEngine, humanReviewService, refundCaseRepository,
                objectMapper, contextAssembler, agentModelService, null);
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
                objectMapper, contextAssembler, null, null);
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
        List<Long> retrievalChunkIds = agentContext.policySnippets().stream()
                .map(RetrievalSnippet::chunkId)
                .toList();
        SafeModelResult<MockIntentClassifier.IntentResult> intentResult;
        if (agentModelService == null) {
            MockIntentClassifier.IntentResult deterministicIntent = intentClassifier.classify(request.message());
            intentResult = new SafeModelResult<>(deterministicIntent,
                    new ModelSafetyDecision(ModelSafetyDecisionType.ALLOW, "DETERMINISTIC_INTENT", List.of(), null),
                    false);
        } else {
            intentResult = agentModelService.classifyIntentAndExtractSlotsSafely(
                    request.sessionId(), null, contextEventIds, retrievalChunkIds, request.message());
        }
        MockIntentClassifier.IntentResult intent = intentResult.value();
        if (intentResult.decision().type() != ModelSafetyDecisionType.ALLOW) {
            return replyWithoutWorkflow(intent, agentContext, intentResult.decision().safeAnswer(), true);
        }
        if (isKnowledgeExplanationIntent(intent.intent())) {
            return handleKnowledgeIntent(request, agentContext, contextEventIds, retrievalChunkIds, intent);
        }
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

        // Why: Escalate to human review if automated rules require manual verification,
        // preventing incorrect automated decisions and ensuring compliance for edge cases.
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

        AnswerabilityDecision effectiveAnswerabilityDecision = lockBusinessDecision(
                agentContext.answerabilityDecision(), workflowResult);
        recordFinalAnswerability(effectiveAnswerabilityDecision);

        String answer;
        boolean fallbackUsed = false;
        try {
            // Why: Generate a strict rule-compliant deterministic answer first to prevent LLM hallucinations,
            // then use LLM only to format it into a friendly customer support tone.
            AnswerabilityDecision answerabilityDecision = effectiveAnswerabilityDecision;
            if (answerabilityDecision != null
                    && isKnowledgeExplanationIntent(intent.intent())
                    && answerabilityDecision.status() == travelcare_agent.answerability.AnswerabilityStatus.UNANSWERABLE
                    && answerabilityDecision.requiredAction() == AnswerabilityRequiredAction.FALLBACK_REPLY) {
                answer = answerabilityDecision.fallbackMessage();
                fallbackUsed = true;
            } else {
                String deterministicAnswer = responseGenerator.generate(intent, workflowResult, agentContext);
                if (agentModelService == null) {
                    answer = deterministicAnswer;
                } else {
                    String authoritativeDecision = refundCaseRepository
                            .findByWorkflowId(workflowResult.workflow().getId())
                            .map(refundCase -> refundCase.getStatus().name())
                            .orElse(null);
                    ModelSafetyContext safetyContext = new ModelSafetyContext(
                            "RESPONSE_GENERATION", Set.of(intent.intent()), false, false,
                            CitationPolicy.FORBIDDEN, List.of(), agentContext.policySnippets(), true,
                            deterministicAnswer, authoritativeDecision, LocalDateTime.now());
                    SafeModelResult<String> safeAnswer = agentModelService.generateCustomerAnswerSafely(
                            request.sessionId(), workflowResult.workflow().getId(), contextEventIds,
                            retrievalChunkIds, deterministicAnswer, safetyContext);
                    answer = safeAnswer.value();
                    fallbackUsed = safeAnswer.providerFallbackUsed()
                            || safeAnswer.decision().type() != ModelSafetyDecisionType.ALLOW;
                }
            }
        } catch (RuntimeException ex) {
            throw new AgentStageException("FAILED_GENERATION", "RESPONSE_GENERATION_FAILED", agentContext, workflowResult.workflow().getId(), ex);
        }

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
                eventIds,
                effectiveAnswerabilityDecision == null ? null : effectiveAnswerabilityDecision.status().name(),
                effectiveAnswerabilityDecision == null ? null : effectiveAnswerabilityDecision.reasonCode().name(),
                effectiveAnswerabilityDecision == null ? null : effectiveAnswerabilityDecision.requiredAction().name(),
                fallbackUsed,
                effectiveAnswerabilityDecision != null && effectiveAnswerabilityDecision.businessDecisionLocked(),
                effectiveAnswerabilityDecision != null && effectiveAnswerabilityDecision.ragMayExplainBusinessDecision(),
                effectiveAnswerabilityDecision != null && effectiveAnswerabilityDecision.ragMayOverrideBusinessDecision(),
                effectiveAnswerabilityDecision == null ? List.of() : effectiveAnswerabilityDecision.citations(),
                effectiveAnswerabilityDecision == null ? List.of() : effectiveAnswerabilityDecision.rejectedCitationCandidates()
        );
    }

    public record AgentRequest(Long sessionId, Long userId, String message) {
    }

    private static boolean isKnowledgeExplanationIntent(String intent) {
        if (intent == null) {
            return false;
        }
        String normalized = intent.trim().toUpperCase();
        return "FAQ".equals(normalized) || "SOP".equals(normalized) || "KNOWLEDGE_QUERY".equals(normalized);
    }

    private AgentReply handleKnowledgeIntent(
            AgentRequest request,
            AgentContext agentContext,
            List<Long> contextEventIds,
            List<Long> retrievalChunkIds,
            MockIntentClassifier.IntentResult intent
    ) {
        AnswerabilityDecision answerability = agentContext.answerabilityDecision();
        recordFinalAnswerability(answerability);
        if (answerability == null
                || answerability.requiredAction() == AnswerabilityRequiredAction.FALLBACK_REPLY) {
            String fallback = answerability == null || answerability.fallbackMessage() == null
                    ? travelcare_agent.answerability.AnswerabilityService.DEFAULT_FALLBACK_MESSAGE
                    : answerability.fallbackMessage();
            return replyWithoutWorkflow(intent, agentContext, fallback, true);
        }
        String deterministicAnswer = "I found verified policy guidance for this question.";
        if (agentModelService == null) {
            return replyWithoutWorkflow(intent, agentContext, deterministicAnswer, false);
        }
        ModelSafetyContext safetyContext = new ModelSafetyContext(
                "KNOWLEDGE_ANSWER", Set.of(intent.intent()), false, true,
                answerability.citationPolicy(), answerability.citations(), agentContext.policySnippets(), false,
                deterministicAnswer, null, LocalDateTime.now());
        SafeModelResult<String> result = agentModelService.generateCustomerAnswerSafely(
                request.sessionId(), null, contextEventIds, retrievalChunkIds, deterministicAnswer, safetyContext);
        return replyWithoutWorkflow(intent, agentContext, result.value(),
                result.providerFallbackUsed() || result.decision().type() != ModelSafetyDecisionType.ALLOW);
    }

    private AgentReply replyWithoutWorkflow(
            MockIntentClassifier.IntentResult intent,
            AgentContext agentContext,
            String answer,
            boolean fallbackUsed
    ) {
        AnswerabilityDecision decision = agentContext.answerabilityDecision();
        return new AgentReply(
                intent == null ? null : intent.intent(), intent == null ? null : intent.orderNo(),
                null, "NOT_STARTED", answer,
                agentContext.policySnippets().stream().map(RetrievalSnippet::documentId).distinct().toList(),
                agentContext.policySnippets().stream().map(RetrievalSnippet::chunkId).toList(),
                agentContext.activeMemories().stream().map(AgentMemory::getId).toList(),
                agentContext.recentEvents().stream().map(SessionEvent::getId).toList(),
                decision == null ? null : decision.status().name(),
                decision == null ? null : decision.reasonCode().name(),
                decision == null ? null : decision.requiredAction().name(),
                fallbackUsed,
                decision != null && decision.businessDecisionLocked(),
                decision != null && decision.ragMayExplainBusinessDecision(),
                decision != null && decision.ragMayOverrideBusinessDecision(),
                decision == null ? List.of() : decision.citations(),
                decision == null ? List.of() : decision.rejectedCitationCandidates());
    }

    private AnswerabilityDecision lockBusinessDecision(AnswerabilityDecision decision,
            WorkflowEngine.WorkflowResult workflowResult) {
        if (decision == null || workflowResult == null || workflowResult.workflow() == null) return decision;
        boolean hasRefundDecision = refundCaseRepository.findByWorkflowId(workflowResult.workflow().getId()).isPresent();
        boolean handoffLocked = workflowResult.workflow().getStatus() == WorkflowStatus.NEED_HUMAN;
        if (!hasRefundDecision && !handoffLocked) return decision;
        return new AnswerabilityDecision(
                decision.status(), decision.reasonCode(), decision.requiredAction(), decision.citationPolicy(),
                decision.evidenceChunkIds(), true, !decision.citations().isEmpty(), false,
                decision.citations(), decision.rejectedCitationCandidates(), decision.fallbackMessage());
    }

    private void recordFinalAnswerability(AnswerabilityDecision decision) {
        if (traceService == null || decision == null) return;
        traceService.recordCurrentSnapshot(TraceSnapshotType.ANSWERABILITY_DECISION, "ANSWERABILITY", null, Map.of(
                "status", decision.status().name(),
                "reasonCode", decision.reasonCode().name(),
                "requiredAction", decision.requiredAction().name(),
                "citationPolicy", decision.citationPolicy().name(),
                "evidenceChunkIds", decision.evidenceChunkIds(),
                "businessDecisionLocked", decision.businessDecisionLocked(),
                "ragMayExplainBusinessDecision", decision.ragMayExplainBusinessDecision(),
                "ragMayOverrideBusinessDecision", decision.ragMayOverrideBusinessDecision()
        ));
        traceService.recordCurrentSnapshot(TraceSnapshotType.CITATION_SUMMARY, "ANSWERABILITY", null, Map.of(
                "citations", decision.citations(),
                "rejectedCitationCandidates", decision.rejectedCitationCandidates()
        ));
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
            List<Long> eventIds,
            String answerabilityStatus,
            String answerabilityReasonCode,
            String requiredAction,
            boolean fallbackUsed,
            boolean businessDecisionLocked,
            boolean ragMayExplainBusinessDecision,
            boolean ragMayOverrideBusinessDecision,
            List<travelcare_agent.answerability.CitationMetadata> citations,
            List<travelcare_agent.answerability.RejectedCitationCandidate> rejectedCitationCandidates
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
