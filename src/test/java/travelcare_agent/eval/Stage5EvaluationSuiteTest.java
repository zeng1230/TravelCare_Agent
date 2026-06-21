package travelcare_agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import travelcare_agent.agent.AgentModelService;
import travelcare_agent.agent.MockIntentClassifier;
import travelcare_agent.agent.prompt.PromptTemplateService;
import travelcare_agent.agent.provider.ChatModelProvider;
import travelcare_agent.agent.provider.MockChatModelProvider;
import travelcare_agent.agent.provider.ModelRequest;
import travelcare_agent.agent.provider.ModelResponse;
import travelcare_agent.agent.provider.ModelUsage;
import travelcare_agent.agentrun.entity.AgentRun;
import travelcare_agent.agentrun.repository.AgentRunRepository;
import travelcare_agent.agentrun.service.AgentRunService;
import travelcare_agent.enums.OrderStatus;
import travelcare_agent.policy.RefundEligibilityDecision;
import travelcare_agent.policy.RefundEligibilityPolicy;
import travelcare_agent.workflow.workflows.OrderRefundInquiryWorkflow;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class Stage5EvaluationSuiteTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-06-01T08:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );

    @Test
    void generatesReportForMockProviderPolicyBoundaryAndInvalidJsonFallback() throws Exception {
        RefundEligibilityPolicy policy = new RefundEligibilityPolicy(CLOCK);
        RefundEligibilityDecision eligible = policy.evaluate(order("ORD-5001", true, LocalDateTime.now(CLOCK).plusHours(48)), 1001L);
        RefundEligibilityDecision ineligible = policy.evaluate(order("ORD-5002", false, LocalDateTime.now(CLOCK).plusHours(48)), 1001L);

        MockChatModelProvider mockProvider = new MockChatModelProvider();
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentModelService mockModelService = new AgentModelService(
                mockProvider,
                mockProvider,
                new PromptTemplateService(),
                new AgentRunService(repository),
                new ObjectMapper()
        );
        mockModelService.classifyIntentAndExtractSlots(501L, null, List.of(701L), "Can I refund order ORD-5001?");
        mockModelService.classifyIntentAndExtractSlots(502L, null, List.of(702L), "Can I refund order ORD-5002?");
        MockIntentClassifier.IntentResult missingOrder = mockModelService.classifyIntentAndExtractSlots(
                503L, null, List.of(703L), "I want a refund"
        );

        ChatModelProvider invalidProvider = new ChatModelProvider() {
            public String providerName() { return "deepseek"; }
            public ModelResponse call(ModelRequest request) {
                return new ModelResponse("not-json", "deepseek-chat", "deepseek",
                        new ModelUsage(1, 1, 2), 1, "stop", null);
            }
        };
        AgentModelService modelService = new AgentModelService(
                invalidProvider,
                mockProvider,
                new PromptTemplateService(),
                new AgentRunService(repository),
                new ObjectMapper()
        );
        String fallbackAnswer = modelService.generateCustomerAnswer(500L, 600L, List.of(700L), "deterministic fallback");

        assertThat(eligible.status().name()).isEqualTo("ELIGIBLE");
        assertThat(ineligible.status().name()).isEqualTo("INELIGIBLE");
        assertThat(missingOrder.orderNo()).isNull();
        assertThat(fallbackAnswer).isEqualTo("deterministic fallback");
        assertThat(repository.findAll()).extracting(AgentRun::getRunType)
                .containsExactly(
                        "INTENT_CLASSIFICATION", "INTENT_CLASSIFICATION", "INTENT_CLASSIFICATION",
                        "RESPONSE_GENERATION"
                );
        assertThat(repository.findAll().get(3).getStatus()).isEqualTo("FALLBACK_SUCCESS");
        assertThat(repository.findAll().get(3).getFallbackUsed()).isTrue();

        List<AgentRun> runs = repository.findAll();
        List<EvaluationCaseResult> results = List.of(
                result("STAGE5-001", "Eligible refund remains policy-controlled", "ELIGIBLE", runs.get(0)),
                result("STAGE5-002", "Ineligible refund remains policy-controlled", "INELIGIBLE", runs.get(1)),
                result("STAGE5-003", "Missing order number is extracted as null", "NEED_HUMAN", runs.get(2)),
                result("STAGE5-004", "Invalid provider JSON uses deterministic fallback", "FALLBACK", runs.get(3))
        );
        Path report = new EvaluationReportWriter().write("stage5", results, "PASS");
        String markdown = Files.readString(report);

        assertThat(markdown).contains("## Agent Runs");
        assertThat(markdown).contains("STAGE5-001", "STAGE5-002", "STAGE5-003", "STAGE5-004");
    }

    private static OrderRefundInquiryWorkflow.OrderSnapshot order(String orderNo, boolean refundable, LocalDateTime departureTime) {
        return new OrderRefundInquiryWorkflow.OrderSnapshot(
                1L, orderNo, 1001L, OrderStatus.PAID, refundable, new BigDecimal("100.00"), departureTime
        );
    }

    private static EvaluationCaseResult result(String id, String description, String decision, AgentRun run) {
        return new EvaluationCaseResult(
                id, description, description, "RESPONDED", "RESPONDED", decision, decision,
                false, List.of(), false, List.of(), List.of(), List.of(), true, false,
                run.getId(), run.getStatus(), "/api/agent-runs/" + run.getId() + "/replay", true, ""
        );
    }

    private static class InMemoryAgentRunRepository implements AgentRunRepository {
        private final ConcurrentHashMap<Long, AgentRun> store = new ConcurrentHashMap<>();
        private long nextId = 1L;

        public AgentRun save(AgentRun run) {
            if (run.getId() == null) run.setId(nextId++);
            store.put(run.getId(), run);
            return run;
        }

        public Optional<AgentRun> findById(Long id) { return Optional.ofNullable(store.get(id)); }
        public List<AgentRun> findBySessionId(Long sessionId, long pageNo, long pageSize) {
            return findAll().stream().filter(run -> sessionId.equals(run.getSessionId())).toList();
        }
        public long countBySessionId(Long sessionId) { return findBySessionId(sessionId, 1, 100).size(); }
        public List<AgentRun> findAll() {
            return new ArrayList<>(store.values()).stream().sorted(Comparator.comparing(AgentRun::getId)).toList();
        }
    }
}
