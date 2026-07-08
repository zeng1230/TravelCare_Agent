package travelcare_agent.trace;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class RedactionBoundary {
    private static final RedactionService DEFAULT = new RedactionService();
    private static final AtomicReference<RedactionService> CURRENT = new AtomicReference<>(DEFAULT);

    public RedactionBoundary(RedactionService service) {
        register(service);
    }

    public static void register(RedactionService service) {
        CURRENT.set(service == null ? DEFAULT : service);
    }

    public static RedactionService service() {
        return CURRENT.get();
    }

    public static String sanitizeLogField(String value, int limit) {
        return service().sanitizeLogField(value, limit);
    }
}
