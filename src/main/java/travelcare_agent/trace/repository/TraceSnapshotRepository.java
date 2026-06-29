package travelcare_agent.trace.repository;

import travelcare_agent.trace.entity.TraceSnapshot;

import java.util.List;

public interface TraceSnapshotRepository {
    TraceSnapshot save(TraceSnapshot value);

    List<TraceSnapshot> findByTraceId(String traceId);
}
