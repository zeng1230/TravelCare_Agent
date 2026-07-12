package travelcare_agent.refund.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import travelcare_agent.refund.entity.RefundCase;

@Mapper
public interface MyBatisRefundCaseMapper extends BaseMapper<RefundCase> {
    @Update("UPDATE refund_cases SET status=#{c.status}, refund_amount=#{c.refundAmount}, " +
            "policy_result_json=#{c.policyResultJson}, updated_at=#{c.updatedAt}, version=version+1 " +
            "WHERE id=#{c.id} AND tenant_id=#{c.tenantId} AND workflow_id=#{c.workflowId} " +
            "AND status='NEED_HUMAN' AND version=#{expectedVersion}")
    int decideIfNeedHuman(@Param("c") RefundCase refundCase, @Param("expectedVersion") long expectedVersion);
}
