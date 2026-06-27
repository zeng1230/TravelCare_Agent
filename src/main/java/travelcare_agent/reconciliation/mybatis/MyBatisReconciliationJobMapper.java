package travelcare_agent.reconciliation.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.reconciliation.ReconciliationJob;

@Mapper
public interface MyBatisReconciliationJobMapper extends BaseMapper<ReconciliationJob> {
}
