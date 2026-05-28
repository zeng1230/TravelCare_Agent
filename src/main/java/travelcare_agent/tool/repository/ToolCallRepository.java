package travelcare_agent.tool.repository;

import travelcare_agent.tool.entity.ToolCall;

import java.util.Optional;

public interface ToolCallRepository {

    ToolCall save(ToolCall toolCall);

    Optional<ToolCall> findById(Long id);

    Optional<ToolCall> findByIdempotencyKey(String idempotencyKey);
}
