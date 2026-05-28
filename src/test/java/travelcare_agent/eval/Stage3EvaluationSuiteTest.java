package travelcare_agent.eval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.agent.AgentContext;
import travelcare_agent.agent.ContextAssembler;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.conversation.repository.SessionEventRepository;
import travelcare_agent.conversation.service.SessionService;
import travelcare_agent.enums.MemoryType;
import travelcare_agent.memory.entity.AgentMemory;
import travelcare_agent.memory.service.MemoryService;
import travelcare_agent.retrieval.entity.KnowledgeDocument;
import travelcare_agent.retrieval.service.KnowledgeIngestionService;
import travelcare_agent.retrieval.service.RetrievalQuery;
import travelcare_agent.retrieval.service.RetrievalService;
import travelcare_agent.retrieval.service.RetrievalSnippet;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
public class Stage3EvaluationSuiteTest {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionEventRepository sessionEventRepository;

    @Autowired
    private KnowledgeIngestionService ingestionService;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private ContextAssembler contextAssembler;

    @Test
    void testCase1_EligibleRefundWithCitations() {
        // 1. Ingest an active refund policy
        KnowledgeDocument doc = ingestionService.ingest(
                "Active Refund SOP",
                "REFUND_SOP",
                "http://example.com/refund-sop",
                "Refund Policy: eligible refunds can be processed for PAID, refundable orders departing after 24 hours.",
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(30)
        );

        // 2. Create session and query for valid refundable order (ORD-1001 is paid, refundable, and departs in 5 days)
        SessionService.CreateSessionResult sessionResult = sessionService.createSession(1001L, "WEB");
        Long sessionId = sessionResult.sessionId();

        SessionService.SendMessageResult result = sessionService.sendMessage(
                sessionId,
                "Can I refund order ORD-1001?",
                "idempotency-case1",
                false
        );

        // 3. Verify reply answer says eligible
        assertThat(result.answer()).contains("is eligible for refund inquiry");
        assertThat(result.assistantEventId()).isNotNull();

        // 4. Verify assistant reply metadata contains retrievalChunkIds
        SessionEvent assistantEvent = sessionEventRepository.findBySessionIdOrderBySeqNo(sessionId).stream()
                .filter(e -> e.getId().equals(result.assistantEventId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Assistant event not found"));
        String metadata = assistantEvent.getMetadataJson();
        assertThat(metadata).contains("retrievalChunkIds");

        // Verify the chunk ID from retrieval service matches the metadata citation list
        List<RetrievalSnippet> snippets = retrievalService.retrieve(
                new RetrievalQuery(sessionId, 1001L, "Can I refund order ORD-1001?", List.of("REFUND_SOP"), 5)
        );
        assertThat(snippets).isNotEmpty();
        Long expectedChunkId = snippets.get(0).chunkId();
        assertThat(metadata).contains(String.valueOf(expectedChunkId));
    }

    @Test
    void testCase2_IneligibleRefundDoesNotOverrideRules() {
        // 1. Ingest loose policy ("everybody gets a refund")
        ingestionService.ingest(
                "Loose Refund Policy",
                "REFUND_SOP",
                "http://example.com/loose-sop",
                "Loose Refund Policy: everybody gets a refund, even for non-refundable orders.",
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(30)
        );

        // 2. Create session and query for a non-refundable order (ORD-1002 is paid, but refundable = false)
        SessionService.CreateSessionResult sessionResult = sessionService.createSession(1001L, "WEB");
        Long sessionId = sessionResult.sessionId();

        SessionService.SendMessageResult result = sessionService.sendMessage(
                sessionId,
                "Can I refund order ORD-1002?",
                "idempotency-case2",
                false
        );

        // 3. Assert the system returns INELIGIBLE, proving RAG does not override hardcoded rules
        assertThat(result.answer()).contains("is not eligible for refund inquiry because order is marked non-refundable");
    }

    @Test
    void testCase3_UserPreferenceMemoryIncludedButSafe() {
        // 1. Write a USER_PREFERENCE memory
        AgentMemory memory = memoryService.createMemory(
                1001L,
                null,
                null,
                MemoryType.USER_PREFERENCE,
                "tone_preference",
                "Prefers polite tone",
                BigDecimal.valueOf(0.95),
                null,
                null
        );

        // 2. Create session and run refund workflow for ORD-1001
        SessionService.CreateSessionResult sessionResult = sessionService.createSession(1001L, "WEB");
        Long sessionId = sessionResult.sessionId();

        SessionService.SendMessageResult result = sessionService.sendMessage(
                sessionId,
                "Can I refund order ORD-1001?",
                "idempotency-case3",
                false
        );

        // 3. Assert preference is loaded in AgentContext
        AgentContext agentContext = contextAssembler.assemble(sessionId, "Can I refund order ORD-1001?");
        boolean hasPreference = agentContext.activeMemories().stream()
                .anyMatch(m -> m.getMemoryKey().equals("tone_preference") && m.getMemoryValue().equals("Prefers polite tone"));
        assertThat(hasPreference).isTrue();

        // 4. Assert memory ID is in assistant reply metadata
        SessionEvent assistantEvent = sessionEventRepository.findBySessionIdOrderBySeqNo(sessionId).stream()
                .filter(e -> e.getId().equals(result.assistantEventId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Assistant event not found"));
        String metadata = assistantEvent.getMetadataJson();
        assertThat(metadata).contains("memoryIds");
        assertThat(metadata).contains(String.valueOf(memory.getId()));

        // 5. Assert the preference had 0 impact on refund decisions (it remains eligible)
        assertThat(result.answer()).contains("is eligible for refund inquiry");
    }

    @Test
    void testCase4_ExpiredKnowledgeIsIgnored() {
        // 1. Ingest policy with effective_to in the past
        KnowledgeDocument doc = ingestionService.ingest(
                "Expired Refund SOP",
                "REFUND_SOP",
                "http://example.com/expired-sop",
                "Expired Policy: refund amount is double for delayed flights.",
                LocalDateTime.now().minusDays(5),
                LocalDateTime.now().minusDays(1)
        );

        // 2. Search for a query matching its content
        List<RetrievalSnippet> snippets = retrievalService.retrieve(
                new RetrievalQuery(1L, 1001L, "double for delayed flights", List.of("REFUND_SOP"), 5)
        );

        // 3. Assert search yields 0 citations from this expired chunk
        boolean containsExpired = snippets.stream()
                .anyMatch(s -> s.documentId().equals(doc.getId()));
        assertThat(containsExpired).isFalse();
    }

    @Test
    void testCase5_DuplicateAsyncRequestReusesResult() {
        SessionService.CreateSessionResult sessionResult = sessionService.createSession(1001L, "WEB");
        Long sessionId = sessionResult.sessionId();

        String idempotencyKey = "async-duplicate-test-key";
        String content = "Can I refund order ORD-1001?";

        // Submit the first async request
        SessionService.SendMessageResult result1 = sessionService.sendMessage(
                sessionId,
                content,
                idempotencyKey,
                true
        );

        assertThat(result1.answer()).isEqualTo("ACCEPTED");
        assertThat(result1.workflowId()).isNotNull();
        assertThat(result1.taskId()).isNotNull();

        // Submit the duplicate async request
        SessionService.SendMessageResult result2 = sessionService.sendMessage(
                sessionId,
                content,
                idempotencyKey,
                true
        );

        // Assert that duplicate async request reuses the cached result
        assertThat(result2.answer()).isEqualTo("ACCEPTED");
        assertThat(result2.workflowId()).isEqualTo(result1.workflowId());
        assertThat(result2.taskId()).isEqualTo(result1.taskId());
        assertThat(result2.userEventId()).isEqualTo(result1.userEventId());
    }
}
