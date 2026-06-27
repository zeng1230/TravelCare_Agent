package travelcare_agent.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import travelcare_agent.common.result.Result;
import travelcare_agent.security.JwtTokenService;

import java.util.List;

@RestController
@RequestMapping("/api/dev/auth")
@Profile({"local", "dev", "test"})
@ConditionalOnProperty(prefix = "travelcare.security", name = "dev-auth-enabled", havingValue = "true")
public class DevAuthController {
    private final JwtTokenService tokenService;

    public DevAuthController(JwtTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/token")
    public Result<TokenResponse> token(@RequestBody TokenRequest request) {
        Long userId = request.userId() == null ? 1001L : request.userId();
        String tenantId = request.tenantId() == null || request.tenantId().isBlank() ? "default" : request.tenantId();
        List<String> roles = request.roles() == null || request.roles().isEmpty() ? List.of("USER") : request.roles();
        return Result.success(new TokenResponse(tokenService.issue(userId, tenantId, roles), userId, tenantId, roles));
    }

    public record TokenRequest(Long userId, String tenantId, List<String> roles) {
    }

    public record TokenResponse(String token, Long userId, String tenantId, List<String> roles) {
    }
}
