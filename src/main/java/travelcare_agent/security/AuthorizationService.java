package travelcare_agent.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.conversation.entity.Session;
import travelcare_agent.conversation.repository.SessionRepository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.repository.WorkflowRepository;

@Service
public class AuthorizationService {
    private final SecurityContextFacade security;
    private final SessionRepository sessions;
    private final WorkflowRepository workflows;

    public AuthorizationService(SecurityContextFacade security, SessionRepository sessions, WorkflowRepository workflows) {
        this.security = security;
        this.sessions = sessions;
        this.workflows = workflows;
    }

    public CurrentUser currentUser() {
        return security.currentUser();
    }

    public Long sessionUserForCreate(Long requestedUserId) {
        CurrentUser user = currentUser();
        if (user.isAdmin()) {
            return requestedUserId == null ? user.userId() : requestedUserId;
        }
        if (requestedUserId != null && !requestedUserId.equals(user.userId())) {
            throw new AccessDeniedException("Cannot create session for another user");
        }
        return user.userId();
    }

    public void requireSessionAccess(Long sessionId) {
        CurrentUser user = currentUser();
        if (user.isAdmin() || user.hasRole("OPERATOR")) {
            return;
        }
        Session session = sessions.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "session not found"));
        if (!user.userId().equals(session.getUserId()) || !user.tenantId().equals(session.getTenantId())) {
            throw new AccessDeniedException("Session access denied");
        }
    }

    public void requireWorkflowAccess(Long workflowId) {
        CurrentUser user = currentUser();
        if (user.isAdmin() || user.hasRole("OPERATOR")) {
            return;
        }
        Workflow workflow = workflows.findById(workflowId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Workflow not found: " + workflowId));
        requireSessionAccess(workflow.getSessionId());
    }

    public void requireUserPathAccess(Long userId) {
        CurrentUser user = currentUser();
        if (user.isAdmin()) {
            return;
        }
        if (!user.userId().equals(userId)) {
            throw new AccessDeniedException("User access denied");
        }
    }
}
