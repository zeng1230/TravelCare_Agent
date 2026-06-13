package travelcare_agent.tool;

import org.springframework.stereotype.Service;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.enums.IdempotencyStatus;
import travelcare_agent.tool.entity.IdempotencyKey;
import travelcare_agent.tool.repository.IdempotencyKeyRepository;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class IdempotencyService {

    private static final int DEFAULT_TTL_HOURS = 24;

    private final IdempotencyKeyRepository repository;

    public IdempotencyService(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    public Decision begin(String scope, String idempotencyKey, String requestHash) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.IDEMPOTENCY_WRITE);
        Optional<IdempotencyKey> existing = repository.findByKey(idempotencyKey);
        if (existing.isEmpty()) {
            repository.save(IdempotencyKey.running(
                    idempotencyKey,
                    scope,
                    requestHash,
                    LocalDateTime.now().plusHours(DEFAULT_TTL_HOURS)
            ));
            return Decision.proceed();
        }

        IdempotencyKey key = existing.get();
        if (!key.getRequestHash().equals(requestHash) || !key.getScope().equals(scope)) {
            throw new BusinessException(ResultCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (key.getStatus() == IdempotencyStatus.SUCCESS && key.getResultId() != null) {
            return Decision.reuse(key.getResultId());
        }
        return Decision.proceed();
    }

    public void markSuccess(String idempotencyKey, String resultType, Long resultId) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.IDEMPOTENCY_WRITE);
        IdempotencyKey key = repository.findByKey(idempotencyKey)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Idempotency key not found"));
        key.succeed(resultType, resultId);
        repository.save(key);
    }

    public void markFailed(String idempotencyKey) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.IDEMPOTENCY_WRITE);
        repository.findByKey(idempotencyKey).ifPresent(key -> {
            key.fail();
            repository.save(key);
        });
    }

    public record Decision(boolean reuse, Long resultId) {
        static Decision proceed() {
            return new Decision(false, null);
        }

        static Decision reuse(Long resultId) {
            return new Decision(true, resultId);
        }
    }
}
