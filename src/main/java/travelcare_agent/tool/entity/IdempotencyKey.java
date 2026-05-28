package travelcare_agent.tool.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import travelcare_agent.enums.IdempotencyStatus;

import java.time.LocalDateTime;

@TableName("idempotency_keys")
public class IdempotencyKey {

    @TableId
    private String idempotencyKey;
    private String scope;
    private String requestHash;
    private IdempotencyStatus status;
    private String resultType;
    private Long resultId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;

    public static IdempotencyKey running(String idempotencyKey, String scope, String requestHash, LocalDateTime expiresAt) {
        IdempotencyKey key = new IdempotencyKey();
        key.setIdempotencyKey(idempotencyKey);
        key.setScope(scope);
        key.setRequestHash(requestHash);
        key.setStatus(IdempotencyStatus.RUNNING);
        key.setExpiresAt(expiresAt);
        key.setCreatedAt(LocalDateTime.now());
        return key;
    }

    public void succeed(String resultType, Long resultId) {
        this.status = IdempotencyStatus.SUCCESS;
        this.resultType = resultType;
        this.resultId = resultId;
    }

    public void fail() {
        this.status = IdempotencyStatus.FAILED;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public void setStatus(IdempotencyStatus status) {
        this.status = status;
    }

    public String getResultType() {
        return resultType;
    }

    public void setResultType(String resultType) {
        this.resultType = resultType;
    }

    public Long getResultId() {
        return resultId;
    }

    public void setResultId(Long resultId) {
        this.resultId = resultId;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
