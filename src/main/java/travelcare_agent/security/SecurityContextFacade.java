package travelcare_agent.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityContextFacade {
    public CurrentUser currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user)) {
            throw new AccessDeniedException("Authenticated user is required");
        }
        return user;
    }

    public boolean hasRole(String role) {
        return currentUser().hasRole(role);
    }
}
