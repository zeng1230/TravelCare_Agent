package travelcare_agent.dryrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import travelcare_agent.trace.entity.TraceSnapshot;

@Component
public class SnapshotRetrievalExecutor {
    private final ObjectMapper objectMapper;
    public SnapshotRetrievalExecutor(ObjectMapper objectMapper){this.objectMapper=objectMapper;}
    public JsonNode execute(TraceSnapshot snapshot){try{return objectMapper.readTree(snapshot.getPayloadJson());}catch(Exception ex){throw new IllegalArgumentException("INVALID_RETRIEVAL_SNAPSHOT",ex);}}
}
