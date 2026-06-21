package travelcare_agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import travelcare_agent.agent.prompt.PromptTemplateService;
import travelcare_agent.agent.provider.ChatModelProvider;
import travelcare_agent.agent.provider.MockChatModelProvider;
import travelcare_agent.agent.provider.ModelCallException;
import travelcare_agent.agent.provider.ModelRequest;
import travelcare_agent.agent.provider.ModelResponse;
import travelcare_agent.agent.provider.ModelUsage;
import travelcare_agent.agentrun.entity.AgentRun;
import travelcare_agent.agentrun.repository.AgentRunRepository;
import travelcare_agent.agentrun.service.AgentRunService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class AgentModelServiceTest {

    @Test
    void recordsPromptVersionForEachSuccessfulModelCall() {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        AgentModelService service = service(new MockChatModelProvider(), repository);

        MockIntentClassifier.IntentResult intent = service.classifyIntentAndExtractSlots(
                100L, null, List.of(11L), "Can I refund order ORD-10?"
        );
        String answer = service.generateCustomerAnswer(
                100L, 200L, List.of(11L), "deterministic answer"
        );

        assertThat(intent.intent()).isEqualTo("REFUND_INQUIRY");
        assertThat(answer).isEqualTo("deterministic answer");
        assertThat(repository.findAll())
                .extracting(AgentRun::getRunType)
                .containsExactly("INTENT_CLASSIFICATION", "RESPONSE_GENERATION");
        assertThat(repository.findAll())
                .extracting(AgentRun::getPromptVersion)
                .containsExactly("intent-classifier-v1", "response-generator-v1");
        assertThat(repository.findAll())
                .allMatch(run -> "SUCCEEDED".equals(run.getStatus()))
                .allMatch(run -> "mock".equals(run.getProvider()));
    }

    @Test
    void invalidJsonFallsBackWithoutReturningRawText() {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        ChatModelProvider invalidProvider = new ChatModelProvider() {
            @Override
            public String providerName() {
                return "deepseek";
            }

            @Override
            public ModelResponse call(ModelRequest request) {
                return new ModelResponse("raw text that must not reach the customer", "deepseek-chat",
                        "deepseek", new ModelUsage(3, 4, 7), 1, "stop", null);
            }
        };
        AgentModelService service = service(invalidProvider, repository);

        String answer = service.generateCustomerAnswer(
                100L, 200L, List.of(11L), "deterministic fallback answer"
        );

        assertThat(answer).isEqualTo("deterministic fallback answer");
        assertThat(answer).doesNotContain("raw text");
        assertThat(repository.findAll())
                .extracting(AgentRun::getRunType)
                .containsExactly("RESPONSE_GENERATION", "FALLBACK");
        assertThat(repository.findAll().get(0).getStatus()).isEqualTo("FAILED_GENERATION");
        assertThat(repository.findAll().get(0).getErrorCode()).isEqualTo("MODEL_INVALID_RESPONSE");
        assertThat(repository.findAll().get(1).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(repository.findAll().get(1).getProvider()).isEqualTo("mock");
    }

    @Test
    void providerExceptionFallsBackToDeterministicIntentClassification() {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        ChatModelProvider failingProvider = new ChatModelProvider() {
            public ModelResponse call(ModelRequest request) {
                throw new ModelCallException("MODEL_TIMEOUT", "timed out");
            }
            public String providerName() { return "deepseek"; }
        };
        AgentModelService service = service(failingProvider, repository);

        MockIntentClassifier.IntentResult result = service.classifyIntentAndExtractSlots(
                100L, null, List.of(11L), "refund ORD-10"
        );

        assertThat(result.intent()).isEqualTo("REFUND_INQUIRY");
        assertThat(result.orderNo()).isEqualTo("ORD-10");
        assertThat(repository.findAll()).extracting(AgentRun::getRunType)
                .containsExactly("INTENT_CLASSIFICATION", "FALLBACK");
        assertThat(repository.findAll().get(0).getErrorCode()).isEqualTo("MODEL_TIMEOUT");
    }

    private static AgentModelService service(ChatModelProvider provider, AgentRunRepository repository) {
        return new AgentModelService(
                provider,
                new MockChatModelProvider(),
                new PromptTemplateService(),
                new AgentRunService(repository),
                new ObjectMapper()
        );
    }

    private static class InMemoryAgentRunRepository implements AgentRunRepository {
        private final ConcurrentHashMap<Long, AgentRun> store = new ConcurrentHashMap<>();
        private long nextId = 1L;

        @Override
        public AgentRun save(AgentRun agentRun) {
            if (agentRun.getId() == null) {
                agentRun.setId(nextId++);
            }
            store.put(agentRun.getId(), agentRun);
            return agentRun;
        }

        @Override
        public Optional<AgentRun> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<AgentRun> findBySessionId(Long sessionId, long pageNo, long pageSize) {
            return findAll().stream().filter(run -> sessionId.equals(run.getSessionId())).toList();
        }

        @Override
        public long countBySessionId(Long sessionId) {
            return findBySessionId(sessionId, 1, 100).size();
        }

        @Override
        public List<AgentRun> findAll() {
            return new ArrayList<>(store.values()).stream()
                    .sorted(Comparator.comparing(AgentRun::getId))
                    .toList();
        }
    }
}
