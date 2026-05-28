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
    public Workflow save(Workflow workflow) {
        if (workflow.getId() == null) {
            mapper.insert(workflow);
        } else {
            mapper.updateById(workflow);
        }
        return workflow;
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
