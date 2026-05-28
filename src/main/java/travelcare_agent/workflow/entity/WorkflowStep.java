package travelcare_agent.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import travelcare_agent.enums.WorkflowStepStatus;

import java.time.LocalDateTime;

@TableName("workflow_steps")
public class WorkflowStep {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workflowId;
    private String stepName;
    private WorkflowStepStatus status;
    private String inputJson;
    private String outputJson;
    private String errorCode;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public static WorkflowStep start(Long workflowId, String stepName, String inputJson) {
        WorkflowStep step = new WorkflowStep();
        step.setWorkflowId(workflowId);
        step.setStepName(stepName);
        step.setStatus(WorkflowStepStatus.RUNNING);
        step.setInputJson(inputJson);
        step.setStartedAt(LocalDateTime.now());
        return step;
    }

    public void succeed(String outputJson) {
        this.status = WorkflowStepStatus.SUCCESS;
        this.outputJson = outputJson;
        this.errorCode = null;
        this.finishedAt = LocalDateTime.now();
    }

    public void fail(String errorCode, String outputJson) {
        this.status = WorkflowStepStatus.FAILED;
        this.errorCode = errorCode;
        this.outputJson = outputJson;
        this.finishedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }

    public WorkflowStepStatus getStatus() {
        return status;
    }

    public void setStatus(WorkflowStepStatus status) {
        this.status = status;
    }

    public String getInputJson() {
        return inputJson;
    }

    public void setInputJson(String inputJson) {
        this.inputJson = inputJson;
    }

    public String getOutputJson() {
        return outputJson;
    }

    public void setOutputJson(String outputJson) {
        this.outputJson = outputJson;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
