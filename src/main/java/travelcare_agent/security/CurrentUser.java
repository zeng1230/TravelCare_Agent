package travelcare_agent.security;

import java.util.Set;

public record CurrentUser(Long userId, String tenantId, Set<String> roles) {
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole("ADMIN");
    }
}
