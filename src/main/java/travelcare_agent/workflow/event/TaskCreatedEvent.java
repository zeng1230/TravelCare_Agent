package travelcare_agent.workflow.event;

public class TaskCreatedEvent {
    private final Long taskId;
    private final Long sessionId;
    private final Long workflowId;
    private final String correlationId;
    private final String traceId;
    private final String parentSpanId;

    public TaskCreatedEvent(Long taskId, Long sessionId, Long workflowId, String correlationId) {
        this(taskId, sessionId, workflowId, correlationId, null, null);
    }
    public TaskCreatedEvent(Long taskId, Long sessionId, Long workflowId, String correlationId, String traceId, String parentSpanId) {
        this.taskId = taskId;
        this.sessionId = sessionId;
        this.workflowId = workflowId;
        this.correlationId = correlationId;
        this.traceId = traceId;
        this.parentSpanId = parentSpanId;
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
    public String getTraceId() { return traceId; }
    public String getParentSpanId() { return parentSpanId; }
}
