package travelcare_agent.dryrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import travelcare_agent.trace.TraceQueryService;
import travelcare_agent.trace.TraceSnapshotType;
import travelcare_agent.trace.entity.TraceSnapshot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DryRunReadinessChecker {
    private static final EnumSet<TraceSnapshotType> REQUIRED = EnumSet.of(
            TraceSnapshotType.USER_INPUT,
            TraceSnapshotType.CONTEXT_SUMMARY,
            TraceSnapshotType.RETRIEVAL_SUMMARY,
            TraceSnapshotType.MODEL_INPUT,
            TraceSnapshotType.MODEL_OUTPUT,
            TraceSnapshotType.TOOL_REQUEST,
            TraceSnapshotType.TOOL_RESULT,
            TraceSnapshotType.POLICY_INPUT,
            TraceSnapshotType.POLICY_DECISION,
            TraceSnapshotType.WORKFLOW_PATH,
            TraceSnapshotType.FINAL_OUTPUT
    );
    private final TraceQueryService traceQueryService;
    private final ObjectMapper objectMapper;

    public DryRunReadinessChecker(TraceQueryService traceQueryService, ObjectMapper objectMapper) {
        this.traceQueryService = traceQueryService;
        this.objectMapper = objectMapper;
    }

    public DryRunReadinessResult check(String traceId, String providerMode) {
        TraceQueryService.TraceDetail detail = traceQueryService.get(traceId);
        List<String> missing = new ArrayList<>();
        if (Boolean.TRUE.equals(detail.run().getDryRun())) {
            missing.add("ORIGINAL_TRACE_REQUIRED");
        }
        if (!"mock".equalsIgnoreCase(providerMode)) {
            missing.add("MOCK_PROVIDER_REQUIRED");
        }

        Map<String, TraceSnapshot> latest = detail.snapshots().stream()
                .collect(Collectors.toMap(TraceSnapshot::getSnapshotType, Function.identity(), (left, right) -> right));
        for (TraceSnapshotType type : REQUIRED) {
            TraceSnapshot snapshot = latest.get(type.name());
            if (snapshot == null || !validJson(snapshot.getPayloadJson())) {
                missing.add(type.name());
            }
        }
        requireFields(latest.get(TraceSnapshotType.USER_INPUT.name()), missing, "USER_INPUT", "message", "userId");
        requireFields(latest.get(TraceSnapshotType.POLICY_INPUT.name()), missing, "POLICY_INPUT", "evaluatedAt", "currentUserId", "order");
        requireOrder(latest.get(TraceSnapshotType.POLICY_INPUT.name()), missing);
        requireToolResult(latest.get(TraceSnapshotType.TOOL_RESULT.name()), missing);
        return missing.isEmpty() ? DryRunReadinessResult.executable() : DryRunReadinessResult.rejected(missing.stream().distinct().toList());
    }

    private boolean validJson(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            return objectMapper.readTree(json).isObject();
        } catch (Exception ex) {
            return false;
        }
    }

    private void requireFields(TraceSnapshot snapshot, List<String> missing, String label, String... fields) {
        if (snapshot == null || !validJson(snapshot.getPayloadJson())) return;
        try {
            JsonNode node = objectMapper.readTree(snapshot.getPayloadJson());
            for (String field : fields) {
                if (node.path(field).isMissingNode() || node.path(field).isNull()) missing.add(label + "." + field);
            }
        } catch (Exception ignored) {
            missing.add(label);
        }
    }

    private void requireOrder(TraceSnapshot snapshot, List<String> missing) {
        if (snapshot == null || !validJson(snapshot.getPayloadJson())) return;
        try {
            JsonNode order = objectMapper.readTree(snapshot.getPayloadJson()).path("order");
            for (String field : List.of("orderId", "orderNo", "userId", "status", "refundable", "paidAmount", "departureTime")) {
                if (order.path(field).isMissingNode() || order.path(field).isNull()) missing.add("POLICY_INPUT.order." + field);
            }
        } catch (Exception ignored) {
            missing.add("POLICY_INPUT.order");
        }
    }

    private void requireToolResult(TraceSnapshot snapshot, List<String> missing) {
        if (snapshot == null || !validJson(snapshot.getPayloadJson())) return;
        try {
            JsonNode node = objectMapper.readTree(snapshot.getPayloadJson());
            if (node.path("toolName").isMissingNode() || !node.path("result").isObject()) missing.add("TOOL_RESULT.result");
        } catch (Exception ignored) {
            missing.add("TOOL_RESULT");
        }
    }
}
