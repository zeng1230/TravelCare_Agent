package travelcare_agent.workflow;

public interface ExecutableWorkflow {

    String type();

    WorkflowEngine.WorkflowResult execute(WorkflowEngine.WorkflowContext context);
}
