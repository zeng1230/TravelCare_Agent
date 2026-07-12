package travelcare_agent.workflow;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.enums.WorkflowStatus;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.entity.WorkflowStep;
import travelcare_agent.workflow.repository.WorkflowRepository;
import travelcare_agent.workflow.repository.WorkflowStepRepository;
import travelcare_agent.trace.*;
import travelcare_agent.observability.TravelCareMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;

@Service
public class WorkflowEngine {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository stepRepository;
    private final WorkflowRegistry registry;
    private final TraceService traceService;
    private final TravelCareMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowEngine(
            WorkflowRepository workflowRepository,
            WorkflowStepRepository stepRepository,
            WorkflowRegistry registry,
            TraceService traceService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) TravelCareMetrics metrics
    ) {
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
        this.registry = registry;
        this.traceService = traceService;
        this.metrics = metrics;
    }
    public WorkflowEngine(WorkflowRepository workflowRepository, WorkflowStepRepository stepRepository, WorkflowRegistry registry) {
        this(workflowRepository, stepRepository, registry, null, null);
    }

    @Transactional
    public WorkflowResult start(String workflowType, WorkflowCommand command) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_WRITE);
        Workflow workflow = workflowRepository.insert(Workflow.create(command.sessionId(), workflowType));
        transition(workflow, WorkflowStatus.RUNNING, "COLLECTING_ORDER_REFERENCE", "{}",
                List.of(WorkflowStatus.CREATED));
        return executeTraced(workflowType, command, workflow);
    }

    @Transactional
    public WorkflowResult resume(Long workflowId, String workflowType, WorkflowCommand command) {
        travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_WRITE);
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found"));
        if (workflow.getStatus() != WorkflowStatus.CREATED && workflow.getStatus() != WorkflowStatus.RUNNING) {
            throw new BusinessException(ResultCode.HUMAN_REVIEW_STATE_CONFLICT,
                    "Workflow is not runnable");
        }
        transition(workflow, WorkflowStatus.RUNNING, "COLLECTING_ORDER_REFERENCE", "{}",
                List.of(WorkflowStatus.CREATED, WorkflowStatus.RUNNING));
        return executeTraced(workflowType, command, workflow);
    }

    private WorkflowResult executeTraced(String workflowType, WorkflowCommand command, Workflow workflow) {
        Instant startedAt = Instant.now();
        if (metrics != null) metrics.workflowStarted(workflowType, workflow.getCurrentStep());
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
            recordWorkflowMetric(workflowType, result.workflow(), Duration.between(startedAt, Instant.now()));
            return result;
        } catch (RuntimeException ex) {
            if (traceService != null) traceService.finishSpanFailure(span, "WORKFLOW_FAILED", ex, Map.of());
            if (metrics != null) metrics.workflowFailed(workflowType, workflow.getCurrentStep(), "WORKFLOW_FAILED",
                    Duration.between(startedAt, Instant.now()));
            throw ex;
        }
    }

    private void recordWorkflowMetric(String workflowType, Workflow workflow, Duration duration) {
        if (metrics == null || workflow.getStatus() == null) return;
        if (workflow.getStatus() == WorkflowStatus.NEED_HUMAN) {
            metrics.workflowNeedHuman(workflowType, workflow.getCurrentStep(), "NEED_HUMAN", duration);
        } else if (workflow.getStatus() == WorkflowStatus.FAILED) {
            metrics.workflowFailed(workflowType, workflow.getCurrentStep(), "WORKFLOW_FAILED", duration);
        } else {
            metrics.workflowCompleted(workflowType, workflow.getStatus().name(), workflow.getCurrentStep(), duration);
        }
    }

    private void transition(Workflow workflow, WorkflowStatus target, String step, String state,
                            List<WorkflowStatus> expectedStatuses) {
        long expectedVersion = requireVersion(workflow);
        workflow.transitionTo(target, step, state);
        int rows = workflowRepository.transitionIfCurrent(workflow, expectedVersion, expectedStatuses);
        if (rows == 0) throw new BusinessException(ResultCode.CONCURRENT_STATE_CONFLICT);
        if (rows != 1) throw new BusinessException(ResultCode.DATA_INTEGRITY_CONFLICT);
        workflow.setVersion(expectedVersion + 1);
    }

    private static long requireVersion(Workflow workflow) {
        if (workflow.getVersion() == null) throw new BusinessException(ResultCode.DATA_INTEGRITY_CONFLICT);
        return workflow.getVersion();
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
            transitionWorkflow(WorkflowStatus.RUNNING, stepName, state("running", stepName),
                    List.of(WorkflowStatus.RUNNING));
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
            transitionWorkflow(WorkflowStatus.RESPONDED, "RESPONDED", stateJson,
                    List.of(WorkflowStatus.RUNNING));
            WorkflowStep step = stepRepository.save(WorkflowStep.start(workflow.getId(), "RESPONDED", "{}"));
            step.succeed(jsonField("answer", answer));
            stepRepository.save(step);
            return new WorkflowResult(workflow, answer);
        }

        public WorkflowResult needHuman(String answer, String reasonCode) {
            travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_WRITE);
            transitionWorkflow(WorkflowStatus.NEED_HUMAN, "NEED_HUMAN", jsonField("reasonCode", reasonCode),
                    List.of(WorkflowStatus.RUNNING));
            WorkflowStep step = stepRepository.save(WorkflowStep.start(workflow.getId(), "NEED_HUMAN", "{}"));
            step.succeed(jsonField("reasonCode", reasonCode));
            stepRepository.save(step);
            return new WorkflowResult(workflow, answer);
        }

        public WorkflowResult failed(String answer, String reasonCode) {
            travelcare_agent.dryrun.SideEffectGuard.checkCurrent(travelcare_agent.dryrun.SideEffectOperation.WORKFLOW_WRITE);
            transitionWorkflow(WorkflowStatus.FAILED, workflow.getCurrentStep(), jsonField("reasonCode", reasonCode),
                    List.of(WorkflowStatus.RUNNING));
            return new WorkflowResult(workflow, answer);
        }

        private void transitionWorkflow(WorkflowStatus target, String step, String state,
                                        List<WorkflowStatus> expectedStatuses) {
            Long current = workflow.getVersion();
            if (current == null) throw new BusinessException(ResultCode.DATA_INTEGRITY_CONFLICT);
            workflow.transitionTo(target, step, state);
            int rows = workflowRepository.transitionIfCurrent(workflow, current, expectedStatuses);
            if (rows == 0) throw new BusinessException(ResultCode.CONCURRENT_STATE_CONFLICT);
            if (rows != 1) throw new BusinessException(ResultCode.DATA_INTEGRITY_CONFLICT);
            workflow.setVersion(current + 1);
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
