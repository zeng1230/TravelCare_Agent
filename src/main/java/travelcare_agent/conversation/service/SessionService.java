package travelcare_agent.conversation.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.agent.AgentOrchestrator;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.conversation.entity.Session;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.conversation.repository.SessionRepository;
import travelcare_agent.enums.SessionEventRole;
import travelcare_agent.tool.IdempotencyService;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
public class SessionService {

    private final SessionRepository repository;
    private final SessionEventService eventService;
    private final AgentOrchestrator agentOrchestrator;
    private final IdempotencyService idempotencyService;
    private final travelcare_agent.workflow.repository.WorkflowRepository workflowRepository;
    private final travelcare_agent.workflow.WorkflowTaskService workflowTaskService;
    private final travelcare_agent.workflow.repository.WorkflowTaskRepository workflowTaskRepository;
    private final ObjectMapper objectMapper;

    public SessionService(
            SessionRepository repository,
            SessionEventService eventService,
            AgentOrchestrator agentOrchestrator,
            IdempotencyService idempotencyService,
            travelcare_agent.workflow.repository.WorkflowRepository workflowRepository,
            travelcare_agent.workflow.WorkflowTaskService workflowTaskService,
            travelcare_agent.workflow.repository.WorkflowTaskRepository workflowTaskRepository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.eventService = eventService;
        this.agentOrchestrator = agentOrchestrator;
        this.idempotencyService = idempotencyService;
        this.workflowRepository = workflowRepository;
        this.workflowTaskService = workflowTaskService;
        this.workflowTaskRepository = workflowTaskRepository;
        this.objectMapper = objectMapper;
    }

    public CreateSessionResult createSession(Long userId, String channel) {
        if (userId == null) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "userId is required");
        }
        if (isBlank(channel)) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "channel is required");
        }

        Session session = repository.save(Session.create(userId, channel.trim()));
        return new CreateSessionResult(session.getId(), session.getStatus().name());
    }

    @Transactional
    public SendMessageResult sendMessage(Long sessionId, String content, String idempotencyKey, Boolean async) {
        Session session = requireExistingSession(sessionId);
        if (isBlank(content)) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "content is required");
        }

        String requestHash = sha256(sessionId + ":" + content + ":" + async);
        IdempotencyService.Decision decision = idempotencyService.begin("sendMessage", idempotencyKey, requestHash);
        if (decision.reuse()) {
            List<SessionEvent> events = eventService.listEvents(sessionId);
            SessionEvent userEvent = events.stream().filter(e -> e.getRole() == SessionEventRole.USER && e.getMetadataJson().contains(idempotencyKey)).findFirst().orElseThrow();
            SessionEvent assistantEvent = events.stream().filter(e -> e.getRole() == SessionEventRole.ASSISTANT && e.getSeqNo() > userEvent.getSeqNo()).findFirst().orElse(null);
            SessionEvent workflowEvent = events.stream().filter(e -> e.getEventType() == travelcare_agent.enums.SessionEventType.WORKFLOW && e.getSeqNo() > userEvent.getSeqNo()).findFirst().orElse(null);
            
            String answer = assistantEvent != null ? assistantEvent.getContent() : "ACCEPTED";
            Long assistantId = assistantEvent != null ? assistantEvent.getId() : null;
            Long workflowEvtId = workflowEvent != null ? workflowEvent.getId() : null;
            
            Long workflowId = null;
            Long taskId = null;
            // Since we don't have a direct query for taskId by correlationId,
            // we could parse it from workflowEvent metadata if it exists.
            if (workflowEvent != null && workflowEvent.getMetadataJson() != null) {
                // "workflowId":"123"
                String meta = workflowEvent.getMetadataJson();
                if (meta.contains("\"workflowId\":\"")) {
                    int start = meta.indexOf("\"workflowId\":\"") + 14;
                    int end = meta.indexOf("\"", start);
                    if (end > start) {
                        try { workflowId = Long.parseLong(meta.substring(start, end)); } catch (Exception ignore) {}
                    }
                }
            }
            if (assistantEvent != null && assistantEvent.getMetadataJson() != null) {
                String meta = assistantEvent.getMetadataJson();
                if (meta.contains("\"taskId\":\"")) {
                    int start = meta.indexOf("\"taskId\":\"") + 10;
                    int end = meta.indexOf("\"", start);
                    if (end > start) {
                        try { taskId = Long.parseLong(meta.substring(start, end)); } catch (Exception ignore) {}
                    }
                }
            }

            if (workflowId == null) {
                List<travelcare_agent.workflow.entity.Workflow> wfs = workflowRepository.findBySessionId(sessionId);
                if (!wfs.isEmpty()) {
                    workflowId = wfs.get(wfs.size() - 1).getId();
                }
            }
            if (workflowId != null && taskId == null) {
                taskId = workflowTaskRepository.findByWorkflowId(workflowId)
                        .map(travelcare_agent.workflow.entity.WorkflowTask::getId)
                        .orElse(null);
            }

            return new SendMessageResult(answer, userEvent.getId(), workflowEvtId, assistantId, workflowId, taskId);
        }

        try {
            SessionEvent userEvent = eventService.appendMessage(
                    sessionId,
                    SessionEventRole.USER,
                    content,
                    metadata("idempotencyKey", idempotencyKey)
            );

            if (Boolean.TRUE.equals(async)) {
                travelcare_agent.workflow.entity.Workflow workflow = travelcare_agent.workflow.entity.Workflow.create(sessionId, "order_refund_inquiry");
                workflowRepository.save(workflow);

                String payload = "{\"message\":\"" + escape(content) + "\"}";
                travelcare_agent.workflow.entity.WorkflowTask task = workflowTaskService.createTask(workflow.getId(), sessionId, "order_refund_inquiry", payload, idempotencyKey);
                
                idempotencyService.markSuccess(idempotencyKey, "sendMessage", userEvent.getId());
                return new SendMessageResult("ACCEPTED", userEvent.getId(), null, null, workflow.getId(), task.getId());
            }

            AgentOrchestrator.AgentReply reply = agentOrchestrator.handle(
                    new AgentOrchestrator.AgentRequest(sessionId, session.getUserId(), content)
            );
            SessionEvent workflowEvent = eventService.appendWorkflowRequested(
                    sessionId,
                    metadata(
                            "workflowType", "order_refund_inquiry",
                            "workflowId", value(reply.workflowId()),
                            "intent", reply.intent(),
                            "orderNo", reply.orderNo(),
                            "workflowStatus", reply.workflowStatus()
                    )
            );

            java.util.Map<String, Object> metaMap = new java.util.HashMap<>();
            metaMap.put("source", "agent_orchestrator");
            metaMap.put("retrievalChunkIds", reply.retrievalChunkIds());
            metaMap.put("memoryIds", reply.memoryIds());
            String metaJson = "{}";
            try {
                metaJson = objectMapper.writeValueAsString(metaMap);
            } catch (Exception ignore) {}

            SessionEvent assistantEvent = eventService.appendMessage(
                    sessionId,
                    SessionEventRole.ASSISTANT,
                    reply.answer(),
                    metaJson
            );

            idempotencyService.markSuccess(idempotencyKey, "sendMessage", assistantEvent.getId());

            return new SendMessageResult(
                    reply.answer(),
                    userEvent.getId(),
                    workflowEvent.getId(),
                    assistantEvent.getId(),
                    reply.workflowId(),
                    null
            );
        } catch (Exception ex) {
            idempotencyService.markFailed(idempotencyKey);
            throw ex;
        }
    }

    public List<SessionEvent> listEvents(Long sessionId) {
        requireExistingSession(sessionId);
        return eventService.listEvents(sessionId);
    }

    private Session requireExistingSession(Long sessionId) {
        if (sessionId == null) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "sessionId is required");
        }
        return repository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "session not found"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String metadata(String key, String value) {
        if (value == null || value.isBlank()) {
            return "{}";
        }
        return "{\"" + escape(key) + "\":\"" + escape(value) + "\"}";
    }

    private static String metadata(String... fields) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (int index = 0; index + 1 < fields.length; index += 2) {
            String value = fields[index + 1];
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!first) {
                json.append(",");
            }
            json.append("\"")
                    .append(escape(fields[index]))
                    .append("\":\"")
                    .append(escape(value))
                    .append("\"");
            first = false;
        }
        return json.append("}").toString();
    }

    private static String value(Long value) {
        return value == null ? null : value.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public record CreateSessionResult(Long sessionId, String status) {
    }

    public record SendMessageResult(
            String answer,
            Long userEventId,
            Long workflowEventId,
            Long assistantEventId,
            Long workflowId,
            Long taskId
    ) {
    }
}
