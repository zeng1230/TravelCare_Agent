package travelcare_agent.trace.repository;
import travelcare_agent.trace.entity.TraceRun; import java.util.List; import java.util.Optional;
public interface TraceRunRepository { TraceRun save(TraceRun value); Optional<TraceRun> findById(Long id); Optional<TraceRun> findByTraceId(String traceId); List<TraceRun> findBySessionId(Long sessionId,long pageNo,long pageSize); long countBySessionId(Long sessionId); }
