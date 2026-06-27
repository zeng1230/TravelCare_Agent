package travelcare_agent.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import travelcare_agent.common.result.Result;
import travelcare_agent.conversation.entity.SessionEvent;
import travelcare_agent.conversation.service.SessionEventService;
import travelcare_agent.conversation.service.SessionService;
import travelcare_agent.agent.ContextAssembler;
import travelcare_agent.agent.AgentContext;
import travelcare_agent.security.AuthorizationService;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final SessionEventService eventService;
    private final ContextAssembler contextAssembler;
    private final AuthorizationService authorizationService;

    @Autowired
    public SessionController(
            SessionService sessionService,
            SessionEventService eventService,
            ContextAssembler contextAssembler,
            AuthorizationService authorizationService
    ) {
        this.sessionService = sessionService;
        this.eventService = eventService;
        this.contextAssembler = contextAssembler;
        this.authorizationService = authorizationService;
    }

    public SessionController(
            SessionService sessionService,
            SessionEventService eventService,
            ContextAssembler contextAssembler
    ) {
        this(sessionService, eventService, contextAssembler, null);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public Result<SessionService.CreateSessionResult> createSession(@RequestBody CreateSessionRequest request) {
        Long userId = authorizationService == null
                ? request.userId()
                : authorizationService.sessionUserForCreate(request.userId());
        return Result.success(sessionService.createSession(userId, request.channel()));
    }

    @PostMapping("/{sessionId}/messages")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public Result<SessionService.SendMessageResult> sendMessage(
            @PathVariable Long sessionId,
            @RequestBody SendMessageRequest request
    ) {
        if (authorizationService != null) {
            authorizationService.requireSessionAccess(sessionId);
        }
        return Result.success(sessionService.sendMessage(sessionId, request.content(), request.idempotencyKey(), request.async()));
    }

    @GetMapping("/{sessionId}/events")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public Result<SessionEventsResponse> listEvents(@PathVariable Long sessionId) {
        if (authorizationService != null) {
            authorizationService.requireSessionAccess(sessionId);
        }
        List<SessionEventResponse> events = sessionService.listEvents(sessionId).stream()
                .map(SessionEventResponse::from)
                .toList();
        return Result.success(new SessionEventsResponse(events));
    }

    @GetMapping("/{sessionId}/context")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public Result<AgentContext> getSessionContext(
            @PathVariable Long sessionId,
            @RequestParam(required = false) String query
    ) {
        if (authorizationService != null) {
            authorizationService.requireSessionAccess(sessionId);
        }
        return Result.success(contextAssembler.assemble(sessionId, query));
    }

    public record CreateSessionRequest(Long userId, String channel) {
    }

    public record SendMessageRequest(String content, String idempotencyKey, Boolean async) {
    }

    public record SessionEventsResponse(List<SessionEventResponse> events) {
    }

    public record SessionEventResponse(
            Long eventId,
            Long sessionId,
            Integer seqNo,
            String eventType,
            String role,
            String content,
            String metadataJson,
            LocalDateTime createdAt
    ) {
        static SessionEventResponse from(SessionEvent event) {
            return new SessionEventResponse(
                    event.getId(),
                    event.getSessionId(),
                    event.getSeqNo(),
                    event.getEventType().name(),
                    event.getRole().name(),
                    event.getContent(),
                    event.getMetadataJson(),
                    event.getCreatedAt()
            );
        }
    }
}
