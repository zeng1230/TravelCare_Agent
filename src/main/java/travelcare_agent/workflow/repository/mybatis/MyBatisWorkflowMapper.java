package travelcare_agent.workflow.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import travelcare_agent.workflow.entity.Workflow;
import java.util.List;

@Mapper
public interface MyBatisWorkflowMapper extends BaseMapper<Workflow> {
    @Update({"<script>",
            "UPDATE workflows SET status=#{workflow.status}, current_step=#{workflow.currentStep},",
            "state_json=#{workflow.stateJson}, updated_at=#{workflow.updatedAt}, version=version+1",
            "WHERE id=#{workflow.id} AND session_id=#{workflow.sessionId} AND version=#{expectedVersion}",
            "AND status IN",
            "<foreach collection='expectedStatuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>",
            "</script>"})
    int transitionIfCurrent(@Param("workflow") Workflow workflow,
                            @Param("expectedVersion") long expectedVersion,
                            @Param("expectedStatuses") List<travelcare_agent.enums.WorkflowStatus> expectedStatuses);
}
