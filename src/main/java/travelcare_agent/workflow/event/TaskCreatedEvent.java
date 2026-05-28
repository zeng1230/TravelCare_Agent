package travelcare_agent.workflow.event;

public class TaskCreatedEvent {
    private final Long taskId;
    private final Long sessionId;
    private final Long workflowId;
    private final String correlationId;

    public TaskCreatedEvent(Long taskId, Long sessionId, Long workflowId, String correlationId) {
        this.taskId = taskId;
        this.sessionId = sessionId;
        this.workflowId = workflowId;
        this.correlationId = correlationId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
