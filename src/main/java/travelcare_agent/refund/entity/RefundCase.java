package travelcare_agent.refund.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import travelcare_agent.enums.RefundCaseStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("refund_cases")
public class RefundCase {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String tenantId;
    private Long userId;
    private Long orderId;
    private Long workflowId;
    private String refundNo;
    private RefundCaseStatus status;
    private Long version;
    private BigDecimal refundAmount;
    private String reason;
    private String policyResultJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static RefundCase create(
            Long userId,
            Long orderId,
            Long workflowId,
            RefundCaseStatus status,
            BigDecimal refundAmount,
            String reason,
            String policyResultJson
    ) {
        LocalDateTime now = LocalDateTime.now();
        RefundCase refundCase = new RefundCase();
        refundCase.setTenantId("default");
        refundCase.setUserId(userId);
        refundCase.setOrderId(orderId);
        refundCase.setWorkflowId(workflowId);
        refundCase.setStatus(status);
        refundCase.setVersion(0L);
        refundCase.setRefundAmount(refundAmount);
        refundCase.setReason(reason);
        refundCase.setPolicyResultJson(policyResultJson);
        refundCase.setCreatedAt(now);
        refundCase.setUpdatedAt(now);
        return refundCase;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
    }

    public String getRefundNo() {
        return refundNo;
    }

    public void setRefundNo(String refundNo) {
        this.refundNo = refundNo;
    }

    public RefundCaseStatus getStatus() {
        return status;
    }

    public void setStatus(RefundCaseStatus status) {
        this.status = status;
    }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getPolicyResultJson() {
        return policyResultJson;
    }

    public void setPolicyResultJson(String policyResultJson) {
        this.policyResultJson = policyResultJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
