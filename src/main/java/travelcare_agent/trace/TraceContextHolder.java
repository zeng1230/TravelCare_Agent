package travelcare_agent.trace;

import org.slf4j.MDC;

import java.util.ArrayDeque;
import java.util.Deque;

public final class TraceContextHolder {
    private static final ThreadLocal<Deque<TraceContext>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private TraceContextHolder() {
    }

    public static TraceContext current() {
        return STACK.get().peek();
    }

    public static Scope attach(String traceId, String spanId) {
        TraceContext context = new TraceContext(traceId, spanId);
        STACK.get().push(context);
        MDC.put("agentTraceId", traceId);
        MDC.put("agentSpanId", spanId);
        return new Scope();
    }

    public record TraceContext(String traceId, String spanId) {
    }

    public static final class Scope implements AutoCloseable {
        private boolean closed;

        public void close() {
            if (closed) return;
            closed = true;
            Deque<TraceContext> s = STACK.get();
            if (!s.isEmpty()) s.pop();
            TraceContext c = s.peek();
            if (c == null) {
                STACK.remove();
                MDC.remove("agentTraceId");
                MDC.remove("agentSpanId");
            } else {
                MDC.put("agentTraceId", c.traceId());
                MDC.put("agentSpanId", c.spanId());
            }
        }
    }
}
