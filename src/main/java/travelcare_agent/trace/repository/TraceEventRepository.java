package travelcare_agent.trace.repository;

import travelcare_agent.trace.entity.TraceEvent;

import java.util.List;

public interface TraceEventRepository {
    TraceEvent save(TraceEvent value);

    List<TraceEvent> findByTraceId(String traceId);
}
