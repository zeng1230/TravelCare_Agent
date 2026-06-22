package travelcare_agent.agentrun;

import org.junit.jupiter.api.Test;
import travelcare_agent.agentrun.entity.AgentRun;
import travelcare_agent.agentrun.repository.AgentRunRepository;
import travelcare_agent.agentrun.service.AgentRunService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunServiceTest {

    @Test
    void recordsOnlyControlledModelSafetySummary() {
        AgentRunService service = new AgentRunService(new InMemoryAgentRunRepository());
        AgentRun run = service.startModelCall(new AgentRunService.ModelCallStart(
                100L, 200L, "RESPONSE_GENERATION", "mock", "mock", "mock-stage10a",
                "response-generator-v1", List.of(11L), List.of(21L), "a".repeat(64)));

        service.completeModelCall(run, new AgentRunService.ModelCallCompletion(
                null, null, "b".repeat(64), 1, 2, 3, false, 10L,
                "SUCCESS", null, null, "SUCCESS", "BLOCK", "UNSAFE_COMMITMENT",
                "[{\"code\":\"UNSAFE_COMMITMENT\",\"severity\":\"CRITICAL\"}]"));

        assertThat(run.getProviderStatus()).isEqualTo("SUCCESS");
        assertThat(run.getSafetyDecision()).isEqualTo("BLOCK");
        assertThat(run.getSafetyReasonCode()).isEqualTo("UNSAFE_COMMITMENT");
        assertThat(run.getRiskFlagsJson()).contains("UNSAFE_COMMITMENT", "CRITICAL");
        assertThat(run.getRiskFlagsJson()).doesNotContain("answerDraft", "raw response", "Authorization");
    }

    @Test
    void recordsContextAndOutputHashesWithoutLongText() {
        AgentRunService service = new AgentRunService(new InMemoryAgentRunRepository());

        AgentRun run = service.startRun(
                100L,
                200L,
                300L,
                "corr-001",
                "SYNC_REPLY",
                "session_service",
                "SYSTEM"
        );

        service.markContextReady(
                run.getId(),
                List.of(11L, 12L),
                List.of(21L),
                List.of(31L),
                "{\"workflowId\":200,\"status\":\"RESPONDED\",\"currentStep\":\"RESPONDED\",\"stateHash\":\"abc\"}",
                "mock-agent-v1",
                "refund-inquiry-template-v1"
        );

        AgentRun succeeded = service.markSucceeded(run.getId(), 41L, "assistant answer with customer facing text");

        assertThat(succeeded.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(succeeded.getInputEventIdsJson()).isEqualTo("[11,12]");
        assertThat(succeeded.getRetrievalChunkIdsJson()).isEqualTo("[21]");
        assertThat(succeeded.getMemoryIdsJson()).isEqualTo("[31]");
        assertThat(succeeded.getOutputEventId()).isEqualTo(41L);
        assertThat(succeeded.getContextHash()).hasSize(64);
        assertThat(succeeded.getContextSnapshotHash()).isEqualTo(succeeded.getContextHash());
        assertThat(succeeded.getAnswerHash()).hasSize(64);
        assertThat(succeeded.getAnswerHash()).doesNotContain("assistant answer");
        assertThat(succeeded.getLatencyMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void marksFailuresWithSafeErrorSummary() {
        AgentRunService service = new AgentRunService(new InMemoryAgentRunRepository());
        AgentRun run = service.startRun(100L, null, null, null, "SYNC_REPLY", "session_service", "SYSTEM");

        AgentRun failed = service.markFailed(
                run.getId(),
                "FAILED_GENERATION",
                "GENERATION_ERROR",
                new IllegalStateException("internal stack details should be truncated and safe")
        );

        assertThat(failed.getStatus()).isEqualTo("FAILED_GENERATION");
        assertThat(failed.getErrorCode()).isEqualTo("GENERATION_ERROR");
        assertThat(failed.getErrorMessage()).contains("internal stack details");
        assertThat(failed.getErrorMessage()).doesNotContain("java.lang.IllegalStateException");
        assertThat(failed.getLatencyMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void markFailedDoesNotOverwriteSucceededTerminalState() {
        AgentRunService service = new AgentRunService(new InMemoryAgentRunRepository());
        AgentRun run = service.startRun(100L, null, null, null, "SYNC_REPLY", "session_service", "SYSTEM");
        AgentRun succeeded = service.markSucceeded(run.getId(), 41L, "answer");

        AgentRun afterFailureAttempt = service.markFailed(
                run.getId(),
                "FAILED_GENERATION",
                "GENERATION_ERROR",
                new IllegalStateException("late failure")
        );

        assertThat(afterFailureAttempt.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(afterFailureAttempt.getOutputEventId()).isEqualTo(41L);
        assertThat(afterFailureAttempt.getAnswerHash()).isEqualTo(succeeded.getAnswerHash());
        assertThat(afterFailureAttempt.getErrorCode()).isNull();
    }

    @Test
    void markSucceededDoesNotOverwriteFailedTerminalState() {
        AgentRunService service = new AgentRunService(new InMemoryAgentRunRepository());
        AgentRun run = service.startRun(100L, null, null, null, "SYNC_REPLY", "session_service", "SYSTEM");
        AgentRun failed = service.markFailed(
                run.getId(),
                "FAILED_CONTEXT",
                "CONTEXT_ASSEMBLY_FAILED",
                new IllegalStateException("context failed")
        );

        AgentRun afterSuccessAttempt = service.markSucceeded(run.getId(), 41L, "late answer");

        assertThat(afterSuccessAttempt.getStatus()).isEqualTo("FAILED_CONTEXT");
        assertThat(afterSuccessAttempt.getErrorCode()).isEqualTo("CONTEXT_ASSEMBLY_FAILED");
        assertThat(afterSuccessAttempt.getErrorMessage()).isEqualTo(failed.getErrorMessage());
        assertThat(afterSuccessAttempt.getOutputEventId()).isNull();
        assertThat(afterSuccessAttempt.getAnswerHash()).isNull();
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
            return store.values().stream()
                    .filter(run -> sessionId.equals(run.getSessionId()))
                    .sorted(Comparator.comparing(AgentRun::getCreatedAt).reversed())
                    .skip((pageNo - 1) * pageSize)
                    .limit(pageSize)
                    .toList();
        }

        @Override
        public long countBySessionId(Long sessionId) {
            return store.values().stream()
                    .filter(run -> sessionId.equals(run.getSessionId()))
                    .count();
        }

        @Override
        public List<AgentRun> findAll() {
            return new ArrayList<>(store.values());
        }
    }
}
