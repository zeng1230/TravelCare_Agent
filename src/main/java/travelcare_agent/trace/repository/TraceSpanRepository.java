package travelcare_agent.trace.repository;

import travelcare_agent.trace.entity.TraceSpan;

import java.util.List;
import java.util.Optional;

public interface TraceSpanRepository {
    TraceSpan save(TraceSpan value);

    Optional<TraceSpan> findBySpanId(String spanId);

    List<TraceSpan> findByTraceId(String traceId);
}
