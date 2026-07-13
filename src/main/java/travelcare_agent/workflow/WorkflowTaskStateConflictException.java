package travelcare_agent.workflow;

public class WorkflowTaskStateConflictException extends RuntimeException {
    private final Long taskId;

    public WorkflowTaskStateConflictException(Long taskId, String message) {
        super(message);
        this.taskId = taskId;
    }

    public Long getTaskId() {
        return taskId;
    }
}
