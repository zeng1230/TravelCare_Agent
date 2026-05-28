package travelcare_agent.workflow;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.entity.WorkflowStep;
import travelcare_agent.workflow.repository.WorkflowRepository;
import travelcare_agent.workflow.repository.WorkflowStepRepository;

@Service
public class WorkflowEngine {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository stepRepository;
    private final WorkflowRegistry registry;

    public WorkflowEngine(
            WorkflowRepository workflowRepository,
            WorkflowStepRepository stepRepository,
            WorkflowRegistry registry
    ) {
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
        this.registry = registry;
    }

    @Transactional
    public WorkflowResult start(String workflowType, WorkflowCommand command) {
        Workflow workflow = workflowRepository.save(Workflow.create(command.sessionId(), workflowType));
        workflow.transitionTo(WorkflowStatus.RUNNING, "COLLECTING_ORDER_REFERENCE", "{}");
        workflowRepository.save(workflow);
        return registry.require(workflowType).execute(new WorkflowContext(command, workflow, workflowRepository, stepRepository));
    }

    @Transactional
    public WorkflowResult resume(Long workflowId, String workflowType, WorkflowCommand command) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found"));
        workflow.transitionTo(WorkflowStatus.RUNNING, "COLLECTING_ORDER_REFERENCE", "{}");
        workflowRepository.save(workflow);
        return registry.require(workflowType).execute(new WorkflowContext(command, workflow, workflowRepository, stepRepository));
    }

    public record WorkflowCommand(
            Long sessionId,
            Long userId,
            Long orderId,
            String orderNo,
            String message
    ) {
    }

    public record WorkflowResult(Workflow workflow, String answer) {
    }

    public static class WorkflowContext {

        private final WorkflowCommand command;
        private final Workflow workflow;
        private final WorkflowRepository workflowRepository;
        private final WorkflowStepRepository stepRepository;

        WorkflowContext(
                WorkflowCommand command,
                Workflow workflow,
                WorkflowRepository workflowRepository,
                WorkflowStepRepository stepRepository
        ) {
            this.command = command;
            this.workflow = workflow;
            this.workflowRepository = workflowRepository;
            this.stepRepository = stepRepository;
        }

        public WorkflowCommand command() {
            return command;
        }

        public Workflow workflow() {
            return workflow;
        }

        public WorkflowStep startStep(String stepName, String inputJson) {
            workflow.transitionTo(WorkflowStatus.RUNNING, stepName, state("running", stepName));
            workflowRepository.save(workflow);
            return stepRepository.save(WorkflowStep.start(workflow.getId(), stepName, inputJson));
        }

        public void succeedStep(WorkflowStep step, String outputJson) {
            step.succeed(outputJson);
            stepRepository.save(step);
        }

        public void failStep(WorkflowStep step, String errorCode, String outputJson) {
            step.fail(errorCode, outputJson);
            stepRepository.save(step);
        }

        public WorkflowResult responded(String answer, String stateJson) {
            workflow.transitionTo(WorkflowStatus.RESPONDED, "RESPONDED", stateJson);
            workflowRepository.save(workflow);
            WorkflowStep step = stepRepository.save(WorkflowStep.start(workflow.getId(), "RESPONDED", "{}"));
            step.succeed(jsonField("answer", answer));
            stepRepository.save(step);
            return new WorkflowResult(workflow, answer);
        }

        public WorkflowResult needHuman(String answer, String reasonCode) {
            workflow.transitionTo(WorkflowStatus.NEED_HUMAN, "NEED_HUMAN", jsonField("reasonCode", reasonCode));
            workflowRepository.save(workflow);
            WorkflowStep step = stepRepository.save(WorkflowStep.start(workflow.getId(), "NEED_HUMAN", "{}"));
            step.succeed(jsonField("reasonCode", reasonCode));
            stepRepository.save(step);
            return new WorkflowResult(workflow, answer);
        }

        public WorkflowResult failed(String answer, String reasonCode) {
            workflow.transitionTo(WorkflowStatus.FAILED, workflow.getCurrentStep(), jsonField("reasonCode", reasonCode));
            workflowRepository.save(workflow);
            return new WorkflowResult(workflow, answer);
        }

        private static String state(String status, String stepName) {
            return "{\"status\":\"" + escape(status) + "\",\"step\":\"" + escape(stepName) + "\"}";
        }

        public static String jsonField(String key, String value) {
            return "{\"" + escape(key) + "\":\"" + escape(value) + "\"}";
        }

        public static String escape(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
