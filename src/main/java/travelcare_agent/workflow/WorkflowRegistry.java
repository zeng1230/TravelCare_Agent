package travelcare_agent.workflow;

import org.springframework.stereotype.Component;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class WorkflowRegistry {

    private final Map<String, ExecutableWorkflow> workflows;

    public WorkflowRegistry(List<ExecutableWorkflow> workflows) {
        this.workflows = workflows.stream()
                .collect(Collectors.toUnmodifiableMap(ExecutableWorkflow::type, Function.identity()));
    }

    public ExecutableWorkflow require(String workflowType) {
        ExecutableWorkflow workflow = workflows.get(workflowType);
        if (workflow == null) {
            throw new BusinessException(ResultCode.WORKFLOW_FAILED, "workflow not registered: " + workflowType);
        }
        return workflow;
    }
}
