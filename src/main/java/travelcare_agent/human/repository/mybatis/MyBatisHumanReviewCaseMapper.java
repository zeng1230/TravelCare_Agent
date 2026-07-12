package travelcare_agent.human.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import travelcare_agent.human.entity.HumanReviewCase;

@Mapper
public interface MyBatisHumanReviewCaseMapper extends BaseMapper<HumanReviewCase> {
    @Update("UPDATE human_review_cases SET status='ASSIGNED', assigned_to=#{c.assignedTo}, " +
            "updated_at=#{c.updatedAt}, version=version+1 WHERE id=#{c.id} AND tenant_id=#{c.tenantId} " +
            "AND status='OPEN' AND version=#{expectedVersion}")
    int assignIfOpen(@Param("c") HumanReviewCase hrCase, @Param("expectedVersion") long expectedVersion);

    @Update("UPDATE human_review_cases SET status='RESOLVED', resolution=#{c.resolution}, " +
            "resolution_note=#{c.resolutionNote}, resolved_by=#{c.resolvedBy}, resolved_at=#{c.resolvedAt}, " +
            "updated_at=#{c.updatedAt}, version=version+1 WHERE id=#{c.id} AND tenant_id=#{c.tenantId} " +
            "AND status IN ('OPEN','ASSIGNED') AND version=#{expectedVersion}")
    int resolveIfCurrent(@Param("c") HumanReviewCase hrCase, @Param("expectedVersion") long expectedVersion);
}
