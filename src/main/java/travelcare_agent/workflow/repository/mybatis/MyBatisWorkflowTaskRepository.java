package travelcare_agent.workflow.repository.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;
import travelcare_agent.workflow.entity.WorkflowTask;
import travelcare_agent.workflow.repository.WorkflowTaskRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisWorkflowTaskRepository implements WorkflowTaskRepository {

    private final MyBatisWorkflowTaskMapper mapper;

    public MyBatisWorkflowTaskRepository(MyBatisWorkflowTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public WorkflowTask save(WorkflowTask task) {
        if (task.getId() == null) {
            if (task.getCreatedAt() == null) {
                task.setCreatedAt(LocalDateTime.now());
            }
            if (task.getUpdatedAt() == null) {
                task.setUpdatedAt(LocalDateTime.now());
            }
            mapper.insert(task);
        } else {
            task.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(task);
        }
        return task;
    }

    @Override
    public Optional<WorkflowTask> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    @Override
    public Optional<WorkflowTask> findByWorkflowId(Long workflowId) {
        return mapper.selectList(new LambdaQueryWrapper<WorkflowTask>()
                        .eq(WorkflowTask::getWorkflowId, workflowId))
                .stream()
                .findFirst();
    }

    @Override
    public List<WorkflowTask> findPendingTasksCreatedBefore(LocalDateTime dateTime) {
        return mapper.selectList(new LambdaQueryWrapper<WorkflowTask>()
                .eq(WorkflowTask::getStatus, travelcare_agent.enums.WorkflowTaskStatus.PENDING)
                .lt(WorkflowTask::getCreatedAt, dateTime));
    }
}

