package travelcare_agent.conversation.service;

import org.springframework.stereotype.Service;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.conversation.repository.SessionEventRepository;
import travelcare_agent.enums.SessionEventRole;
import travelcare_agent.enums.SessionEventType;

import java.util.List;

@Service
public class SessionEventService {

    private final SessionEventRepository repository;

    public SessionEventService(SessionEventRepository repository) {
        this.repository = repository;
    }

    public SessionEvent appendMessage(Long sessionId, SessionEventRole role, String content, String metadataJson) {
        return append(sessionId, SessionEventType.MESSAGE, role, content, metadataJson);
    }

    public SessionEvent appendWorkflowRequested(Long sessionId, String metadataJson) {
        return append(
                sessionId,
                SessionEventType.WORKFLOW,
                SessionEventRole.SYSTEM,
                "order_refund_inquiry workflow requested",
                metadataJson
        );
    }

    public List<SessionEvent> listEvents(Long sessionId) {
        return repository.findBySessionIdOrderBySeqNo(sessionId);
    }

    private SessionEvent append(
            Long sessionId,
            SessionEventType eventType,
            SessionEventRole role,
            String content,
            String metadataJson
    ) {
        int seqNo = repository.nextSeqNo(sessionId);
        SessionEvent event = SessionEvent.create(sessionId, seqNo, eventType, role, content, metadataJson);
        return repository.save(event);
    }
}
