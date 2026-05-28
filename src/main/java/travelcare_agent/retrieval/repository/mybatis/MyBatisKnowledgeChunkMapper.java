package travelcare_agent.retrieval.repository.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import travelcare_agent.retrieval.entity.KnowledgeChunk;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MyBatisKnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    @Select("<script>" +
            "SELECT c.* FROM knowledge_chunks c " +
            "JOIN knowledge_documents d ON c.document_id = d.id " +
            "WHERE d.status = 'ACTIVE' " +
            "AND (d.effective_from IS NULL OR d.effective_from &lt;= #{now}) " +
            "AND (d.effective_to IS NULL OR d.effective_to &gt;= #{now}) " +
            "<if test='docTypes != null and docTypes.size() > 0'>" +
            "AND d.doc_type IN " +
            "<foreach item='item' index='index' collection='docTypes' open='(' separator=',' close=')'>" +
            "#{item}" +
            "</foreach>" +
            "</if>" +
            "AND MATCH(c.title, c.content, c.keywords) AGAINST(#{queryText} IN NATURAL LANGUAGE MODE) " +
            "LIMIT #{limit}" +
            "</script>")
    List<KnowledgeChunk> searchFulltext(
            @Param("queryText") String queryText,
            @Param("now") LocalDateTime now,
            @Param("docTypes") List<String> docTypes,
            @Param("limit") int limit
    );

    @Select("<script>" +
            "SELECT c.* FROM knowledge_chunks c " +
            "JOIN knowledge_documents d ON c.document_id = d.id " +
            "WHERE d.status = 'ACTIVE' " +
            "AND (d.effective_from IS NULL OR d.effective_from &lt;= #{now}) " +
            "AND (d.effective_to IS NULL OR d.effective_to &gt;= #{now}) " +
            "<if test='docTypes != null and docTypes.size() > 0'>" +
            "AND d.doc_type IN " +
            "<foreach item='item' index='index' collection='docTypes' open='(' separator=',' close=')'>" +
            "#{item}" +
            "</foreach>" +
            "</if>" +
            "AND (" +
            "<foreach item='kw' index='idx' collection='keywords' separator=' OR '>" +
            "c.title LIKE CONCAT('%', #{kw}, '%') OR c.content LIKE CONCAT('%', #{kw}, '%') OR c.keywords LIKE CONCAT('%', #{kw}, '%')" +
            "</foreach>" +
            ") " +
            "LIMIT #{limit}" +
            "</script>")
    List<KnowledgeChunk> searchFallback(
            @Param("keywords") List<String> keywords,
            @Param("now") LocalDateTime now,
            @Param("docTypes") List<String> docTypes,
            @Param("limit") int limit
    );
}
