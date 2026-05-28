package travelcare_agent.retrieval.repository.mybatis;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import travelcare_agent.retrieval.entity.KnowledgeChunk;
import travelcare_agent.retrieval.repository.KnowledgeChunkRepository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class MyBatisKnowledgeChunkRepository implements KnowledgeChunkRepository {

    private final MyBatisKnowledgeChunkMapper mapper;

    public MyBatisKnowledgeChunkRepository(MyBatisKnowledgeChunkMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public KnowledgeChunk save(KnowledgeChunk chunk) {
        if (chunk.getId() == null) {
            mapper.insert(chunk);
        } else {
            mapper.updateById(chunk);
        }
        return chunk;
    }

    @Override
    @Transactional
    public void saveBatch(List<KnowledgeChunk> chunks) {
        for (KnowledgeChunk chunk : chunks) {
            save(chunk);
        }
    }

    @Override
    public List<KnowledgeChunk> searchFulltext(String queryText, LocalDateTime now, List<String> docTypes, int limit) {
        return mapper.searchFulltext(queryText, now, docTypes, limit);
    }

    @Override
    public List<KnowledgeChunk> searchFallback(List<String> keywords, LocalDateTime now, List<String> docTypes, int limit) {
        return mapper.searchFallback(keywords, now, docTypes, limit);
    }
}
