package travelcare_agent.dryrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import travelcare_agent.adapter.order.OrderSnapshot;
import travelcare_agent.trace.TraceSnapshotType;
import travelcare_agent.trace.entity.TraceSnapshot;

@Component
public class SnapshotToolExecutor {
    private final ObjectMapper objectMapper;
    public SnapshotToolExecutor(ObjectMapper objectMapper){this.objectMapper=objectMapper;}
    public OrderSnapshot order(TraceSnapshot snapshot){
        if(snapshot==null||!TraceSnapshotType.TOOL_RESULT.name().equals(snapshot.getSnapshotType()))throw new IllegalArgumentException("TOOL_RESULT_REQUIRED");
        try{JsonNode n=objectMapper.readTree(snapshot.getPayloadJson()).path("result");return objectMapper.treeToValue(n,OrderSnapshot.class);}
        catch(Exception ex){throw new IllegalArgumentException("INVALID_TOOL_RESULT_SNAPSHOT",ex);}
    }
}
