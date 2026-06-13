package travelcare_agent.trace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import travelcare_agent.conversation.repository.SessionEventRepository;
import travelcare_agent.conversation.service.SessionService;
import travelcare_agent.tool.repository.ToolCallRepository;
import travelcare_agent.trace.repository.TraceRunRepository;
import travelcare_agent.trace.repository.TraceSpanRepository;
import travelcare_agent.trace.repository.TraceEventRepository;
import travelcare_agent.trace.repository.TraceSnapshotRepository;
import travelcare_agent.trace.entity.TraceRun;
import travelcare_agent.workflow.repository.WorkflowRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest
class TraceFailureIsolationIntegrationTest {
    @Autowired private SessionService sessionService;
    @Autowired private SessionEventRepository eventRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ToolCallRepository toolCallRepository;
    @MockBean private TraceRunRepository traceRunRepository;
    @MockBean private TraceSpanRepository traceSpanRepository;
    @MockBean private TraceEventRepository traceEventRepository;
    @MockBean private TraceSnapshotRepository traceSnapshotRepository;

    @Test
    void traceRepositoryFailureDoesNotRollbackOrFailSendMessage() {
        when(traceRunRepository.save(any())).thenThrow(new IllegalStateException("trace database unavailable"));
        Long sessionId=sessionService.createSession(1001L,"WEB").sessionId();

        SessionService.SendMessageResult result=sessionService.sendMessage(
                sessionId,"Can I refund order ORD-1001?","trace-failure-isolation-"+System.nanoTime(),false);

        assertThat(result.answer()).contains("ORD-1001");
        assertThat(result.traceAvailable()).isFalse();
        assertThat(result.traceId()).isNull();
        assertThat(eventRepository.findBySessionIdOrderBySeqNo(sessionId)).hasSize(3);
        assertThat(workflowRepository.findBySessionId(sessionId)).isNotEmpty();
        assertThat(toolCallRepository.findByIdempotencyKey(
                workflowRepository.findBySessionId(sessionId).get(0).getId()+"-get-order")).isPresent();
    }

    @Test
    void downstreamTraceWriteFailuresDoNotRollbackBusinessTransaction() {
        reset(traceRunRepository, traceSpanRepository, traceEventRepository, traceSnapshotRepository);
        AtomicReference<TraceRun> stored=new AtomicReference<>();
        when(traceRunRepository.save(any())).thenAnswer(invocation->{TraceRun run=invocation.getArgument(0);stored.set(run);return run;});
        when(traceRunRepository.findByTraceId(any())).thenAnswer(invocation->Optional.ofNullable(stored.get()));
        when(traceSpanRepository.save(any())).thenAnswer(new org.mockito.stubbing.Answer<>() {
            int calls;
            public Object answer(org.mockito.invocation.InvocationOnMock invocation) {
                if (calls++ == 0) return invocation.getArgument(0);
                throw new IllegalStateException("span write failed");
            }
        });
        when(traceSpanRepository.findByTraceId(any())).thenReturn(java.util.List.of());
        when(traceEventRepository.save(any())).thenThrow(new IllegalStateException("event write failed"));
        when(traceSnapshotRepository.save(any())).thenThrow(new IllegalStateException("snapshot write failed"));
        Long sessionId=sessionService.createSession(1001L,"WEB").sessionId();

        SessionService.SendMessageResult result=sessionService.sendMessage(
                sessionId,"Can I refund order ORD-1001?","trace-downstream-failure-"+System.nanoTime(),false);

        assertThat(result.answer()).contains("ORD-1001");
        assertThat(result.traceAvailable()).isTrue();
        assertThat(eventRepository.findBySessionIdOrderBySeqNo(sessionId)).hasSize(3);
        assertThat(workflowRepository.findBySessionId(sessionId)).isNotEmpty();
    }
}
