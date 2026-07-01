package travelcare_agent.tool.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import travelcare_agent.adapter.order.OrderAdapter;
import travelcare_agent.adapter.order.OrderSnapshot;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.tool.ToolService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Component
public class GetOrderTool {

    private static final String TOOL_NAME = "GetOrderTool";

    private final ToolService toolService;
    private final OrderAdapter orderAdapter;
    private final ObjectMapper objectMapper;

    public GetOrderTool(ToolService toolService, OrderAdapter orderAdapter) {
        this.toolService = toolService;
        this.orderAdapter = orderAdapter;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    public GetOrderResult execute(GetOrderRequest request) {
        String requestJson = serialize(request);
        String requestHash = sha256(requestJson);
        ToolService.ToolCommand command = new ToolService.ToolCommand(
                request.sessionId(),
                request.workflowId(),
                request.stepId(),
                TOOL_NAME,
                request.idempotencyKey(),
                requestHash,
                requestJson,
                LocalDateTime.now().plusSeconds(30)
        );
        return toolService.execute(command, GetOrderResult.class, () -> lookup(request)).result();
    }

    private GetOrderResult lookup(GetOrderRequest request) {
        OrderSnapshot order = orderAdapter.getOrder(request.orderId(), request.orderNo(), request.userId())
                .orElseThrow(() -> new BusinessException(ResultCode.ORDER_NOT_FOUND));
        return new GetOrderResult(order);
    }

    private String serialize(GetOrderRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize get-order request", ex);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public record GetOrderRequest(
            Long sessionId,
            Long workflowId,
            Long stepId,
            Long userId,
            Long orderId,
            String orderNo,
            String idempotencyKey
    ) {
    }

    public record GetOrderResult(OrderSnapshot order) {
    }
}
