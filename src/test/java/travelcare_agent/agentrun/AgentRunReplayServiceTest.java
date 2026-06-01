package travelcare_agent.agentrun;

import org.junit.jupiter.api.Test;
import travelcare_agent.agentrun.entity.AgentRun;
import travelcare_agent.agentrun.repository.AgentRunRepository;
import travelcare_agent.agentrun.service.AgentRunReplayService;
import travelcare_agent.audit.entity.AuditLog;
import travelcare_agent.audit.repository.AuditLogRepository;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.conversation.repository.SessionEventRepository;
import travelcare_agent.enums.MemoryType;
import travelcare_agent.enums.SessionEventRole;
import travelcare_agent.enums.SessionEventType;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.memory.repository.AgentMemoryRepository;
import travelcare_agent.retrieval.entity.KnowledgeChunk;
import travelcare_agent.retrieval.repository.KnowledgeChunkRepository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.repository.WorkflowRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunReplayServiceTest {

    @Test
    void replaysPersistedRunContextWithoutReturningLongOrSensitiveValues() {
        TestRepositories repositories = new TestRepositories();
        repositories.agentRuns.save(sampleRun(1L, 100L, 200L, 300L, 41L));
        repositories.agentRuns.save(sampleRun(2L, 100L, 200L, 300L, 42L));
        repositories.events.save(event(11L, 100L, 1, SessionEventRole.USER, "Can I refund order " + "A".repeat(250)));
        repositories.events.save(event(41L, 100L, 2, SessionEventRole.ASSISTANT, "Order is eligible for refund."));
        repositories.chunks.save(chunk(21L, "Refund SOP", "Refund Policy: " + "eligible ".repeat(50)));
        repositories.memories.save(memory(31L, "tone_preference", "never return this raw value"));
        repositories.workflows.save(workflow(200L));
        repositories.auditLogs.save(auditLog(501L, 100L, 200L, "CONTEXT_ASSEMBLED"));

        AgentRunReplayService service = repositories.service();

        AgentRunReplayService.AgentRunReplayResponse replay = service.replay(1L);

        assertThat(replay.agentRun().id()).isEqualTo(1L);
        assertThat(replay.inputEvents()).hasSize(1);
        assertThat(replay.inputEvents().get(0).contentPreview()).hasSize(200);
        assertThat(replay.retrievalChunks()).hasSize(1);
        assertThat(replay.retrievalChunks().get(0).contentPreview()).hasSize(200);
        assertThat(replay.memories()).hasSize(1);
        assertThat(replay.memories().get(0).memoryKey()).isEqualTo("tone_preference");
        assertThat(replay.memories().get(0).toString()).doesNotContain("never return");
        assertThat(replay.workflowSnapshot().agentRunSnapshotJson()).contains("\"RESPONDED\"");
        assertThat(replay.workflowSnapshot().currentWorkflow().workflowId()).isEqualTo(200L);
        assertThat(replay.outputAssistantEvent().eventId()).isEqualTo(41L);
        assertThat(replay.auditActions()).extracting(AgentRunReplayService.AuditActionReplay::action)
                .containsExactly("CONTEXT_ASSEMBLED");
        assertThat(replay.relatedTaskAttempts()).extracting(AgentRunReplayService.RelatedTaskAttempt::agentRunId)
                .containsExactly(1L, 2L);
        assertThat(replay.warnings()).isEmpty();
    }

    @Test
    void addsWarningsWhenReferencedChunkOrMemoryIsMissing() {
        TestRepositories repositories = new TestRepositories();
        repositories.agentRuns.save(sampleRun(1L, 100L, 200L, 300L, null));

        AgentRunReplayService.AgentRunReplayResponse replay = repositories.service().replay(1L);

        assertThat(replay.retrievalChunks()).isEmpty();
        assertThat(replay.memories()).isEmpty();
        assertThat(replay.warnings()).contains(
                "Missing retrieval chunk: 21",
                "Missing memory: 31"
        );
    }

    private static AgentRun sampleRun(Long id, Long sessionId, Long workflowId, Long taskId, Long outputEventId) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setSessionId(sessionId);
        run.setWorkflowId(workflowId);
        run.setTaskId(taskId);
        run.setRunType("ASYNC_WORKER_REPLY");
        run.setSource("workflow_task_worker");
        run.setInputEventIdsJson("[11]");
        run.setRetrievalChunkIdsJson("[21]");
        run.setMemoryIdsJson("[31]");
        run.setWorkflowSnapshotJson("{\"workflowId\":200,\"status\":\"RESPONDED\",\"currentStep\":\"RESPONDED\"}");
        run.setPromptVersion("mock-agent-v1");
        run.setResponseTemplateVersion("refund-inquiry-template-v1");
        run.setContextHash("c".repeat(64));
        run.setAnswerHash("a".repeat(64));
        run.setOutputEventId(outputEventId);
        run.setStatus("SUCCEEDED");
        run.setLatencyMs(30L);
        run.setCreatedAt(LocalDateTime.now().plusSeconds(id));
        return run;
    }

    private static SessionEvent event(Long id, Long sessionId, int seqNo, SessionEventRole role, String content) {
        SessionEvent event = SessionEvent.create(sessionId, seqNo, SessionEventType.MESSAGE, role, content, "{}");
        event.setId(id);
        return event;
    }

    private static KnowledgeChunk chunk(Long id, String title, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setDocumentId(10L);
        chunk.setTitle(title);
        chunk.setContent(content);
        chunk.setSourceUri("https://example.com/refund-sop");
        return chunk;
    }

    private static AgentMemory memory(Long id, String key, String value) {
        AgentMemory memory = new AgentMemory();
        memory.setId(id);
        memory.setMemoryType(MemoryType.USER_PREFERENCE);
        memory.setMemoryKey(key);
        memory.setMemoryValue(value);
        memory.setConfidence(new BigDecimal("0.95"));
        memory.setStatus("ACTIVE");
        return memory;
    }

    private static Workflow workflow(Long id) {
        Workflow workflow = Workflow.create(100L, "ORDER_REFUND_INQUIRY");
        workflow.setId(id);
        workflow.setStatus(WorkflowStatus.RESPONDED);
        workflow.setCurrentStep("RESPONDED");
        workflow.setVersion(3L);
        return workflow;
    }

    private static AuditLog auditLog(Long id, Long sessionId, Long workflowId, String action) {
        AuditLog log = AuditLog.system(sessionId, workflowId, action, "AGENT_CONTEXT", null, null, "{\"chunkIds\":[21]}");
        log.setId(id);
        return log;
    }

    private static class TestRepositories {
        private final InMemoryAgentRunRepository agentRuns = new InMemoryAgentRunRepository();
        private final InMemorySessionEventRepository events = new InMemorySessionEventRepository();
        private final InMemoryKnowledgeChunkRepository chunks = new InMemoryKnowledgeChunkRepository();
        private final InMemoryAgentMemoryRepository memories = new InMemoryAgentMemoryRepository();
        private final InMemoryWorkflowRepository workflows = new InMemoryWorkflowRepository();
        private final InMemoryAuditLogRepository auditLogs = new InMemoryAuditLogRepository();

        AgentRunReplayService service() {
            return new AgentRunReplayService(agentRuns, events, chunks, memories, workflows, auditLogs);
        }
    }

    private static class InMemoryAgentRunRepository implements AgentRunRepository {
        private final ConcurrentHashMap<Long, AgentRun> store = new ConcurrentHashMap<>();

        @Override
        public AgentRun save(AgentRun agentRun) {
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
                    .sorted(Comparator.comparing(AgentRun::getCreatedAt).thenComparing(AgentRun::getId))
                    .toList();
        }

        @Override
        public long countBySessionId(Long sessionId) {
            return findBySessionId(sessionId, 1, Long.MAX_VALUE).size();
        }

        @Override
        public List<AgentRun> findAll() {
            return new ArrayList<>(store.values());
        }
    }

    private static class InMemorySessionEventRepository implements SessionEventRepository {
        private final ConcurrentHashMap<Long, SessionEvent> store = new ConcurrentHashMap<>();

        @Override
        public SessionEvent save(SessionEvent event) {
            store.put(event.getId(), event);
            return event;
        }

        @Override
        public int nextSeqNo(Long sessionId) {
            return 1;
        }

        @Override
        public List<SessionEvent> findBySessionIdOrderBySeqNo(Long sessionId) {
            return store.values().stream()
                    .filter(event -> sessionId.equals(event.getSessionId()))
                    .sorted(Comparator.comparing(SessionEvent::getSeqNo))
                    .toList();
        }
    }

    private static class InMemoryKnowledgeChunkRepository implements KnowledgeChunkRepository {
        private final ConcurrentHashMap<Long, KnowledgeChunk> store = new ConcurrentHashMap<>();

        @Override
        public KnowledgeChunk save(KnowledgeChunk chunk) {
            store.put(chunk.getId(), chunk);
            return chunk;
        }

        @Override
        public Optional<KnowledgeChunk> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public void saveBatch(List<KnowledgeChunk> chunks) {
            chunks.forEach(this::save);
        }

        @Override
        public List<KnowledgeChunk> searchFulltext(String queryText, LocalDateTime now, List<String> docTypes, int limit) {
            return List.of();
        }

        @Override
        public List<KnowledgeChunk> searchFallback(List<String> keywords, LocalDateTime now, List<String> docTypes, int limit) {
            return List.of();
        }
    }

    private static class InMemoryAgentMemoryRepository implements AgentMemoryRepository {
        private final ConcurrentHashMap<Long, AgentMemory> store = new ConcurrentHashMap<>();

        @Override
        public AgentMemory save(AgentMemory memory) {
            store.put(memory.getId(), memory);
            return memory;
        }

        @Override
        public Optional<AgentMemory> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<AgentMemory> findActiveMemories(Long userId, List<MemoryType> types, int limit) {
            return List.of();
        }
    }

    private static class InMemoryWorkflowRepository implements WorkflowRepository {
        private final ConcurrentHashMap<Long, Workflow> store = new ConcurrentHashMap<>();

        @Override
        public Workflow save(Workflow workflow) {
            store.put(workflow.getId(), workflow);
            return workflow;
        }

        @Override
        public Optional<Workflow> findById(Long workflowId) {
            return Optional.ofNullable(store.get(workflowId));
        }

        @Override
        public List<Workflow> findBySessionId(Long sessionId) {
            return List.of();
        }
    }

    private static class InMemoryAuditLogRepository implements AuditLogRepository {
        private final ConcurrentHashMap<Long, AuditLog> store = new ConcurrentHashMap<>();

        @Override
        public AuditLog save(AuditLog auditLog) {
            store.put(auditLog.getId(), auditLog);
            return auditLog;
        }

        @Override
        public List<AuditLog> findBySessionId(Long sessionId) {
            return store.values().stream()
                    .filter(log -> sessionId.equals(log.getSessionId()))
                    .sorted(Comparator.comparing(AuditLog::getId))
                    .toList();
        }

        @Override
        public List<AuditLog> findByWorkflowId(Long workflowId) {
            return store.values().stream()
                    .filter(log -> workflowId.equals(log.getWorkflowId()))
                    .sorted(Comparator.comparing(AuditLog::getId))
                    .toList();
        }
    }
}
