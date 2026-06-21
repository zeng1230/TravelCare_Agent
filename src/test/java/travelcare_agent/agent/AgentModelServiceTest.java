package travelcare_agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import travelcare_agent.agent.prompt.PromptTemplateService;
import travelcare_agent.agent.provider.ChatModelProvider;
import travelcare_agent.agent.provider.AgentProviderProperties;
import travelcare_agent.agent.provider.AgentProviderType;
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
                100L, null, List.of(11L), List.of(101L), "Can I refund order ORD-10?"
        );
        String answer = service.generateCustomerAnswer(
                100L, 200L, List.of(11L), List.of(101L), "deterministic answer"
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
                .allMatch(run -> "SUCCESS".equals(run.getStatus()))
                .allMatch(run -> "mock".equals(run.getProvider()));
        assertThat(repository.findAll())
                .allMatch(run -> "mock".equals(run.getProviderMode()))
                .allMatch(run -> !run.getFallbackUsed())
                .allMatch(run -> run.getRequestHash() != null && run.getRequestHash().length() == 64)
                .allMatch(run -> run.getResponseHash() != null && run.getResponseHash().length() == 64)
                .allMatch(run -> "[101]".equals(run.getRetrievalChunkIdsJson()));
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
                100L, 200L, List.of(11L), List.of(101L), "deterministic fallback answer"
        );

        assertThat(answer).isEqualTo("deterministic fallback answer");
        assertThat(answer).doesNotContain("raw text");
        assertThat(repository.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getRunType()).isEqualTo("RESPONSE_GENERATION");
            assertThat(run.getStatus()).isEqualTo("FALLBACK_SUCCESS");
            assertThat(run.getErrorCode()).isEqualTo("MODEL_INVALID_RESPONSE");
            assertThat(run.getProvider()).isEqualTo("deepseek");
            assertThat(run.getProviderMode()).isEqualTo("deepseek");
            assertThat(run.getFallbackProvider()).isEqualTo("mock");
            assertThat(run.getFallbackModel()).isEqualTo("mock-stage10a");
            assertThat(run.getFallbackUsed()).isTrue();
            assertThat(run.getInputTokens()).isEqualTo(3);
            assertThat(run.getOutputTokens()).isEqualTo(4);
            assertThat(run.getTotalTokens()).isEqualTo(7);
            assertThat(run.getErrorMessage()).isEqualTo("Primary model attempt failed: MODEL_INVALID_RESPONSE");
        });
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
                100L, null, List.of(11L), List.of(), "refund ORD-10"
        );

        assertThat(result.intent()).isEqualTo("REFUND_INQUIRY");
        assertThat(result.orderNo()).isEqualTo("ORD-10");
        assertThat(repository.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getRunType()).isEqualTo("INTENT_CLASSIFICATION");
            assertThat(run.getStatus()).isEqualTo("FALLBACK_SUCCESS");
            assertThat(run.getErrorCode()).isEqualTo("MODEL_TIMEOUT");
        });
    }

    @Test
    void fallbackFailureUpdatesSingleRunAndPreservesSafeDiagnostics() {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        ChatModelProvider primary = failingProvider("deepseek", "MODEL_HTTP_ERROR", "secret body");
        MockChatModelProvider fallback = new MockChatModelProvider() {
            @Override
            public ModelResponse call(ModelRequest request) {
                throw new ModelCallException("MODEL_MOCK_FAILED", "user data and raw response");
            }
        };
        AgentModelService service = service(primary, fallback, repository);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.generateCustomerAnswer(
                        null, null, List.of(), List.of(), "safe answer"))
                .isInstanceOf(ModelCallException.class)
                .hasMessage("Deterministic model fallback failed");

        assertThat(repository.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo("FALLBACK_FAILED");
            assertThat(run.getFallbackUsed()).isTrue();
            assertThat(run.getErrorCode()).isEqualTo("MODEL_FALLBACK_FAILED");
            assertThat(run.getErrorMessage()).isEqualTo(
                    "Primary model attempt failed: MODEL_HTTP_ERROR; fallback model attempt failed: MODEL_MOCK_FAILED");
            assertThat(run.getErrorMessage()).doesNotContain("secret", "user data", "raw response");
        });
    }

    @Test
    void agentRunPersistenceFailureDoesNotReplaceSuccessfulProviderResult() {
        AgentRunRepository failingRepository = new InMemoryAgentRunRepository() {
            @Override
            public AgentRun save(AgentRun agentRun) {
                throw new IllegalStateException("database unavailable");
            }
        };
        AgentModelService service = service(new MockChatModelProvider(), failingRepository);

        assertThat(service.generateCustomerAnswer(null, null, List.of(), List.of(), "answer"))
                .isEqualTo("answer");
    }

    @Test
    void completionPersistenceFailureDoesNotReplaceModelFailure() {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository() {
            private int saves;

            @Override
            public AgentRun save(AgentRun agentRun) {
                if (++saves > 1) {
                    throw new IllegalStateException("database unavailable");
                }
                return super.save(agentRun);
            }
        };
        MockChatModelProvider fallback = new MockChatModelProvider() {
            @Override
            public ModelResponse call(ModelRequest request) {
                throw new ModelCallException("MODEL_MOCK_FAILED", "raw fallback failure");
            }
        };
        AgentModelService service = service(
                failingProvider("deepseek", "MODEL_TIMEOUT", "raw primary failure"), fallback, repository);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.generateCustomerAnswer(
                        null, null, List.of(), List.of(), "answer"))
                .isInstanceOfSatisfying(ModelCallException.class,
                        error -> assertThat(error.code()).isEqualTo("MODEL_FALLBACK_FAILED"))
                .hasMessage("Deterministic model fallback failed");
    }

    @Test
    void promptPreparationFailureProducesFailedRunWithoutCallingFallback() {
        InMemoryAgentRunRepository repository = new InMemoryAgentRunRepository();
        PromptTemplateService failingPrompts = new PromptTemplateService() {
            @Override
            public String render(String promptVersion, String inputJson) {
                throw new IllegalStateException("template contains private input");
            }
        };
        AgentModelService service = new AgentModelService(
                new MockChatModelProvider(), new MockChatModelProvider(), new AgentProviderProperties(),
                failingPrompts, new AgentRunService(repository), new ObjectMapper(), null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.generateCustomerAnswer(
                        null, null, List.of(), List.of(), "private answer"))
                .isInstanceOfSatisfying(ModelCallException.class,
                        error -> assertThat(error.code()).isEqualTo("MODEL_REQUEST_PREPARATION_FAILED"));

        assertThat(repository.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo("FAILED");
            assertThat(run.getFallbackUsed()).isFalse();
            assertThat(run.getPromptVersion()).isEqualTo("response-generator-v1");
            assertThat(run.getErrorMessage()).isEqualTo(
                    "Primary model attempt failed: MODEL_REQUEST_PREPARATION_FAILED");
            assertThat(run.getErrorMessage()).doesNotContain("private input", "private answer");
        });
    }

    private static AgentModelService service(ChatModelProvider provider, AgentRunRepository repository) {
        return service(provider, new MockChatModelProvider(), repository);
    }

    private static AgentModelService service(
            ChatModelProvider provider,
            MockChatModelProvider fallback,
            AgentRunRepository repository
    ) {
        AgentProviderProperties properties = new AgentProviderProperties();
        if ("deepseek".equals(provider.providerName())) {
            properties.setProvider(AgentProviderType.DEEPSEEK);
            properties.setModel("deepseek-chat");
        }
        return new AgentModelService(
                provider,
                fallback,
                properties,
                new PromptTemplateService(),
                new AgentRunService(repository),
                new ObjectMapper(),
                null
        );
    }

    private static ChatModelProvider failingProvider(String name, String code, String message) {
        return new ChatModelProvider() {
            public ModelResponse call(ModelRequest request) {
                throw new ModelCallException(code, message);
            }
            public String providerName() { return name; }
        };
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
