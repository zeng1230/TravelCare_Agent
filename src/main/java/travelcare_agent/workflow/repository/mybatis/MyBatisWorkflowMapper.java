package travelcare_agent.workflow.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.workflow.entity.Workflow;

@Mapper
public interface MyBatisWorkflowMapper extends BaseMapper<Workflow> {
}
