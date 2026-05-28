package travelcare_agent.workflow.repository.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;
import travelcare_agent.workflow.entity.WorkflowStep;
import travelcare_agent.workflow.repository.WorkflowStepRepository;

import java.util.Comparator;
import java.util.List;

@Repository
public class MyBatisWorkflowStepRepository implements WorkflowStepRepository {

    private final MyBatisWorkflowStepMapper mapper;

    public MyBatisWorkflowStepRepository(MyBatisWorkflowStepMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public WorkflowStep save(WorkflowStep step) {
        if (step.getId() == null) {
            mapper.insert(step);
        } else {
            mapper.updateById(step);
        }
        return step;
    }

    @Override
    public List<WorkflowStep> findByWorkflowId(Long workflowId) {
        return mapper.selectList(new LambdaQueryWrapper<WorkflowStep>()
                        .eq(WorkflowStep::getWorkflowId, workflowId))
                .stream()
                .sorted(Comparator.comparing(WorkflowStep::getStartedAt))
                .toList();
    }
}
