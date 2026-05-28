package travelcare_agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.tool.entity.ToolCall;
import travelcare_agent.tool.repository.ToolCallRepository;

import java.time.LocalDateTime;
import java.util.function.Supplier;

@Service
public class ToolService {

    private final ToolCallRepository toolCallRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ToolService(ToolCallRepository toolCallRepository, IdempotencyService idempotencyService) {
        this(toolCallRepository, idempotencyService, new ObjectMapper().findAndRegisterModules());
    }

    ToolService(
            ToolCallRepository toolCallRepository,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper
    ) {
        this.toolCallRepository = toolCallRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    public <T> ToolExecution<T> execute(ToolCommand command, Class<T> resultType, Supplier<T> action) {
        IdempotencyService.Decision decision = idempotencyService.begin(
                "tool_call",
                command.idempotencyKey(),
                command.requestHash()
        );
        if (decision.reuse()) {
            ToolCall cached = toolCallRepository.findById(decision.resultId())
                    .orElseThrow(() -> new IllegalStateException("Cached tool call not found: " + decision.resultId()));
            return new ToolExecution<>(cached, deserialize(cached.getResponseJson(), resultType), true);
        }

        ToolCall toolCall = toolCallRepository.save(ToolCall.running(new ToolCall.ToolCommandFields(
                command.sessionId(),
                command.workflowId(),
                command.stepId(),
                command.toolName(),
                command.idempotencyKey(),
                command.requestHash(),
                command.requestJson(),
                command.timeoutAt()
        )));

        try {
            T result = action.get();
            toolCall.succeed(serialize(result));
            toolCallRepository.save(toolCall);
            idempotencyService.markSuccess(command.idempotencyKey(), "tool_call", toolCall.getId());
            return new ToolExecution<>(toolCall, result, false);
        } catch (BusinessException ex) {
            toolCall.fail(errorJson(ex.getResultCode().code(), ex.getMessage()));
            toolCallRepository.save(toolCall);
            idempotencyService.markFailed(command.idempotencyKey());
            throw ex;
        } catch (RuntimeException ex) {
            toolCall.unknown(errorJson("UNKNOWN_TOOL_ERROR", ex.getMessage()));
            toolCallRepository.save(toolCall);
            idempotencyService.markFailed(command.idempotencyKey());
            throw ex;
        }
    }

    private <T> T deserialize(String responseJson, Class<T> resultType) {
        try {
            return objectMapper.readValue(responseJson, resultType);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize cached tool result", ex);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize tool result", ex);
        }
    }

    private String errorJson(String code, String message) {
        try {
            return objectMapper.writeValueAsString(new ToolError(code, message));
        } catch (JsonProcessingException ex) {
            return "{\"code\":\"" + code + "\"}";
        }
    }

    public record ToolCommand(
            Long sessionId,
            Long workflowId,
            Long stepId,
            String toolName,
            String idempotencyKey,
            String requestHash,
            String requestJson,
            LocalDateTime timeoutAt
    ) {
    }

    public record ToolExecution<T>(ToolCall toolCall, T result, boolean reused) {
    }

    private record ToolError(String code, String message) {
    }
}
