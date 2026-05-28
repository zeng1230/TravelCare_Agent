package travelcare_agent.retrieval.repository.mybatis;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;
import travelcare_agent.retrieval.entity.KnowledgeDocument;
import travelcare_agent.retrieval.repository.KnowledgeDocumentRepository;

import java.util.Optional;

@Repository
public class MyBatisKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final MyBatisKnowledgeDocumentMapper mapper;

    public MyBatisKnowledgeDocumentRepository(MyBatisKnowledgeDocumentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public KnowledgeDocument save(KnowledgeDocument document) {
        if (document.getId() == null) {
            mapper.insert(document);
        } else {
            mapper.updateById(document);
        }
        return document;
    }

    @Override
    public Optional<KnowledgeDocument> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    @Override
    public Optional<KnowledgeDocument> findByContentHash(String contentHash) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<KnowledgeDocument>lambdaQuery()
                .eq(KnowledgeDocument::getContentHash, contentHash)));
    }
}
