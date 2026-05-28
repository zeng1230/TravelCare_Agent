package travelcare_agent.tool.repository;

import travelcare_agent.tool.entity.IdempotencyKey;

import java.util.Optional;

public interface IdempotencyKeyRepository {

    IdempotencyKey save(IdempotencyKey idempotencyKey);

    Optional<IdempotencyKey> findByKey(String idempotencyKey);
}
