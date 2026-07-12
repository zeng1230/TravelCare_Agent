package travelcare_agent.workflow.repository.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;
import travelcare_agent.workflow.entity.Workflow;
import travelcare_agent.workflow.repository.WorkflowRepository;

import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisWorkflowRepository implements WorkflowRepository {

    private final MyBatisWorkflowMapper mapper;

    public MyBatisWorkflowRepository(MyBatisWorkflowMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Workflow insert(Workflow workflow) {
        mapper.insert(workflow);
        return workflow;
    }

    @Override
    public int transitionIfCurrent(Workflow workflow, long expectedVersion,
                                   List<travelcare_agent.enums.WorkflowStatus> expectedStatuses) {
        return mapper.transitionIfCurrent(workflow, expectedVersion, expectedStatuses);
    }

    @Override
    public Optional<Workflow> findById(Long workflowId) {
        return Optional.ofNullable(mapper.selectById(workflowId));
    }

    @Override
    public List<Workflow> findBySessionId(Long sessionId) {
        return mapper.selectList(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getSessionId, sessionId));
    }
}
