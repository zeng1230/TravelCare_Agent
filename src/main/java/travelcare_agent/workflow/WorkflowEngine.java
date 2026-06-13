package travelcare_agent.workflow;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.entity.WorkflowStep;
import travelcare_agent.workflow.repository.WorkflowRepository;
import travelcare_agent.workflow.repository.WorkflowStepRepository;
import travelcare_agent.trace.*;
import java.util.Map;

@Service
public class WorkflowEngine {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository stepRepository;
    private final WorkflowRegistry registry;
    private final TraceService traceService;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowEngine(
            WorkflowRepository workflowRepository,
            WorkflowStepRepository stepRepository,
            WorkflowRegistry registry,
            TraceService traceService
    ) {
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
        this.registry = registry;
        this.traceService = traceService;
    }
    public WorkflowEngine(WorkflowRepository workflowRepository, WorkflowStepRepository stepRepository, WorkflowRegistry registry) {
        this(workflowRepository, stepRepository, registry, null);
    }

    @Transactional
    public WorkflowResult start(String workflowType, WorkflowCommand command) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_WRITE);
        Workflow workflow = workflowRepository.save(Workflow.create(command.sessionId(), workflowType));
        workflow.transitionTo(WorkflowStatus.RUNNING, "COLLECTING_ORDER_REFERENCE", "{}");
        workflowRepository.save(workflow);
        return executeTraced(workflowType, command, workflow);
    }

    @Transactional
    public WorkflowResult resume(Long workflowId, String workflowType, WorkflowCommand command) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_WRITE);
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found"));
        workflow.transitionTo(WorkflowStatus.RUNNING, "COLLECTING_ORDER_REFERENCE", "{}");
        workflowRepository.save(workflow);
        return executeTraced(workflowType, command, workflow);
    }

    private WorkflowResult executeTraced(String workflowType, WorkflowCommand command, Workflow workflow) {
        TraceService.SpanHandle span = traceService == null ? TraceService.SpanHandle.unavailable()
                : traceService.startSpan(SpanType.WORKFLOW, workflowType, Map.of("workflowId", workflow.getId()));
        try (TraceContextHolder.Scope ignored = span.available() ? TraceContextHolder.attach(span.traceId(), span.spanId()) : null) {
            WorkflowResult result = registry.require(workflowType).execute(
                    new WorkflowContext(command, workflow, workflowRepository, stepRepository, traceService));
            if (traceService != null) traceService.recordCurrentSnapshot(TraceSnapshotType.WORKFLOW_PATH,
                    "WORKFLOW", String.valueOf(workflow.getId()), Map.of(
                            "workflowType", workflowType,
                            "status", result.workflow().getStatus().name(),
                            "steps", stepRepository.findByWorkflowId(workflow.getId()).stream().map(step -> Map.of(
                                    "name", step.getStepName(),
                                    "status", step.getStatus().name(),
                                    "errorCode", step.getErrorCode() == null ? "" : step.getErrorCode()
                            )).toList()
                    ));
            if (traceService != null) traceService.finishSpanSuccess(span, "WORKFLOW:" + workflow.getId(), Map.of("status", result.workflow().getStatus().name()));
            return result;
        } catch (RuntimeException ex) {
            if (traceService != null) traceService.finishSpanFailure(span, "WORKFLOW_FAILED", ex, Map.of());
            throw ex;
        }
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
        private final TraceService traceService;

        WorkflowContext(
                WorkflowCommand command,
                Workflow workflow,
                WorkflowRepository workflowRepository,
                WorkflowStepRepository stepRepository,
                TraceService traceService
        ) {
            this.command = command;
            this.workflow = workflow;
            this.workflowRepository = workflowRepository;
            this.stepRepository = stepRepository;
            this.traceService = traceService;
        }

        public WorkflowCommand command() {
            return command;
        }

        public Workflow workflow() {
            return workflow;
        }

        public WorkflowStep startStep(String stepName, String inputJson) {
            travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_STEP_WRITE);
            workflow.transitionTo(WorkflowStatus.RUNNING, stepName, state("running", stepName));
            workflowRepository.save(workflow);
            TraceService.SpanHandle span = traceService == null ? TraceService.SpanHandle.unavailable()
                    : traceService.startSpan(SpanType.WORKFLOW_STEP, stepName, Map.of("workflowId", workflow.getId()));
            WorkflowStep step = WorkflowStep.start(workflow.getId(), stepName, inputJson);
            if (span.available()) { step.setTraceId(span.traceId()); step.setSpanId(span.spanId()); }
            return stepRepository.save(step);
        }

        public void succeedStep(WorkflowStep step, String outputJson) {
            travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_STEP_WRITE);
            step.succeed(outputJson);
            stepRepository.save(step);
            if (traceService != null && step.getSpanId() != null) traceService.finishSpanSuccess(
                    new TraceService.SpanHandle(step.getTraceId(), step.getSpanId(), true), "WORKFLOW_STEP:" + step.getId(), Map.of("output", outputJson));
        }

        public void failStep(WorkflowStep step, String errorCode, String outputJson) {
            travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_STEP_WRITE);
            step.fail(errorCode, outputJson);
            stepRepository.save(step);
            if (traceService != null && step.getSpanId() != null) traceService.finishSpanFailure(
                    new TraceService.SpanHandle(step.getTraceId(), step.getSpanId(), true), errorCode, null, Map.of("output", outputJson));
        }

        public WorkflowResult responded(String answer, String stateJson) {
            travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_WRITE);
            workflow.transitionTo(WorkflowStatus.RESPONDED, "RESPONDED", stateJson);
            workflowRepository.save(workflow);
            WorkflowStep step = stepRepository.save(WorkflowStep.start(workflow.getId(), "RESPONDED", "{}"));
            step.succeed(jsonField("answer", answer));
            stepRepository.save(step);
            return new WorkflowResult(workflow, answer);
        }

        public WorkflowResult needHuman(String answer, String reasonCode) {
            travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_WRITE);
            workflow.transitionTo(WorkflowStatus.NEED_HUMAN, "NEED_HUMAN", jsonField("reasonCode", reasonCode));
            workflowRepository.save(workflow);
            WorkflowStep step = stepRepository.save(WorkflowStep.start(workflow.getId(), "NEED_HUMAN", "{}"));
            step.succeed(jsonField("reasonCode", reasonCode));
            stepRepository.save(step);
            return new WorkflowResult(workflow, answer);
        }

        public WorkflowResult failed(String answer, String reasonCode) {
            travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_WRITE);
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
