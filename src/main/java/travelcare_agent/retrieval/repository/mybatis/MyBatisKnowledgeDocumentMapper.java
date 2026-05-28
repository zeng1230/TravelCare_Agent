package travelcare_agent.retrieval.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import travelcare_agent.retrieval.entity.KnowledgeDocument;

@Mapper
public interface MyBatisKnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
}
