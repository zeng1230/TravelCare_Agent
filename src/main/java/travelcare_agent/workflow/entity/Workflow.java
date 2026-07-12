package travelcare_agent.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import travelcare_agent.enums.WorkflowStatus;

import java.time.LocalDateTime;

@TableName("workflows")
public class Workflow {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long sessionId;
    private String workflowType;
    private WorkflowStatus status;
    private String currentStep;
    private String stateJson;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static Workflow create(Long sessionId, String workflowType) {
        LocalDateTime now = LocalDateTime.now();
        Workflow workflow = new Workflow();
        workflow.setSessionId(sessionId);
        workflow.setWorkflowType(workflowType);
        workflow.setStatus(WorkflowStatus.CREATED);
        workflow.setCurrentStep("CREATED");
        workflow.setStateJson("{}");
        workflow.setVersion(0L);
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        return workflow;
    }

    public void transitionTo(WorkflowStatus status, String currentStep, String stateJson) {
        this.status = status;
        this.currentStep = currentStep;
        this.stateJson = stateJson == null ? "{}" : stateJson;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getWorkflowType() {
        return workflowType;
    }

    public void setWorkflowType(String workflowType) {
        this.workflowType = workflowType;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public void setStatus(WorkflowStatus status) {
        this.status = status;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(String currentStep) {
        this.currentStep = currentStep;
    }

    public String getStateJson() {
        return stateJson;
    }

    public void setStateJson(String stateJson) {
        this.stateJson = stateJson;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
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
