package travelcare_agent.audit.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.audit.entity.AuditLog;

@Mapper
public interface MyBatisAuditLogMapper extends BaseMapper<AuditLog> {
}
