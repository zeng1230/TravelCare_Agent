package travelcare_agent.evaluation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import travelcare_agent.evaluation.scoring.SideEffectCheckResult;

import java.util.*;

@Component
public class EvaluationSideEffectGuard {
    public static final List<String> BUSINESS_TABLES = List.of("sessions", "session_events", "workflows", "workflow_steps", "tool_calls", "idempotency_keys", "audit_logs", "human_review_cases", "workflow_tasks", "refund_cases", "agent_runs", "outbox_events", "reconciliation_jobs", "async_jobs");
    private final JdbcTemplate jdbc;

    public EvaluationSideEffectGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> r = new LinkedHashMap<>();
        for (String t : BUSINESS_TABLES)
            try {
                r.put(t, jdbc.queryForObject("select count(*) from " + t, Long.class));
            } catch (Exception ignored) {
            }
        return r;
    }

    public SideEffectCheckResult compare(Map<String, Long> before) {
        Map<String, Long> after = snapshot();
        boolean safe = before.equals(after);
        return new SideEffectCheckResult(safe, before, after, safe ? null : "business table counts changed");
    }
}
