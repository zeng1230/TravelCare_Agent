package travelcare_agent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.human.service.HumanReviewService;
import travelcare_agent.refund.entity.RefundCase;
import travelcare_agent.refund.repository.RefundCaseRepository;
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

    public AgentOrchestrator(
            MockIntentClassifier intentClassifier,
            MockResponseGenerator responseGenerator,
            WorkflowEngine workflowEngine,
            HumanReviewService humanReviewService,
            RefundCaseRepository refundCaseRepository,
            ObjectMapper objectMapper,
            ContextAssembler contextAssembler
    ) {
        this.intentClassifier = intentClassifier;
        this.responseGenerator = responseGenerator;
        this.workflowEngine = workflowEngine;
        this.humanReviewService = humanReviewService;
        this.refundCaseRepository = refundCaseRepository;
        this.objectMapper = objectMapper;
        this.contextAssembler = contextAssembler;
    }

    public AgentReply handle(AgentRequest request) {
        AgentContext agentContext = contextAssembler.assemble(request.sessionId(), request.message());

        MockIntentClassifier.IntentResult intent = intentClassifier.classify(request.message());
        WorkflowEngine.WorkflowResult workflowResult = workflowEngine.start(
                OrderRefundInquiryWorkflow.TYPE,
                new WorkflowEngine.WorkflowCommand(
                        request.sessionId(),
                        request.userId(),
                        null,
                        intent.orderNo(),
                        request.message()
                )
        );

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

        String answer = responseGenerator.generate(intent, workflowResult, agentContext);

        List<Long> retrievalChunkIds = agentContext.policySnippets().stream()
                .map(RetrievalSnippet::chunkId)
                .toList();
        List<Long> memoryIds = agentContext.activeMemories().stream()
                .map(AgentMemory::getId)
                .toList();

        return new AgentReply(
                intent.intent(),
                intent.orderNo(),
                workflowResult.workflow().getId(),
                workflowResult.workflow().getStatus().name(),
                answer,
                retrievalChunkIds,
                memoryIds
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
            List<Long> retrievalChunkIds,
            List<Long> memoryIds
    ) {
    }
}
