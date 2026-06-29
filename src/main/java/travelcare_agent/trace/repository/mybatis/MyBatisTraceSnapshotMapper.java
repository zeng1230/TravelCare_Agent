package travelcare_agent.trace.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.trace.entity.TraceSnapshot;

@Mapper
public interface MyBatisTraceSnapshotMapper extends BaseMapper<TraceSnapshot> {
}
