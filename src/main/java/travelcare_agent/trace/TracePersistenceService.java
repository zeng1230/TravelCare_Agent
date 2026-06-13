package travelcare_agent.trace;

import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Propagation; import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.trace.entity.*; import travelcare_agent.trace.repository.*;

@Service public class TracePersistenceService {
 private final TraceRunRepository runs; private final TraceSpanRepository spans; private final TraceEventRepository events; private final TraceSnapshotRepository snapshots;
 public TracePersistenceService(TraceRunRepository r,TraceSpanRepository s,TraceEventRepository e,TraceSnapshotRepository sn){runs=r;spans=s;events=e;snapshots=sn;}
 @Transactional(propagation=Propagation.REQUIRES_NEW) public TraceRun saveRun(TraceRun v){return runs.save(v);}
 @Transactional(propagation=Propagation.REQUIRES_NEW) public TraceSpan saveSpan(TraceSpan v){return spans.save(v);}
 @Transactional(propagation=Propagation.REQUIRES_NEW) public TraceEvent saveEvent(TraceEvent v){return events.save(v);}
 @Transactional(propagation=Propagation.REQUIRES_NEW) public TraceSnapshot saveSnapshot(TraceSnapshot v){return snapshots.save(v);}
 @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true) public TraceRun findRun(String traceId){return runs.findByTraceId(traceId).orElseThrow();}
 @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true) public TraceSpan findSpan(String spanId){return spans.findBySpanId(spanId).orElseThrow();}
 @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true) public java.util.List<TraceSpan> findSpans(String traceId){return spans.findByTraceId(traceId);}
}
