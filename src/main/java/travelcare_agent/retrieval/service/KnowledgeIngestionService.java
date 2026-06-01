package travelcare_agent.retrieval.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import travelcare_agent.common.exception.BusinessException;
import travelcare_agent.common.result.ResultCode;
import travelcare_agent.retrieval.entity.KnowledgeChunk;
import travelcare_agent.retrieval.entity.KnowledgeDocument;
import travelcare_agent.retrieval.repository.KnowledgeChunkRepository;
import travelcare_agent.retrieval.repository.KnowledgeDocumentRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeIngestionService {

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;

    public KnowledgeIngestionService(
            KnowledgeDocumentRepository documentRepository,
            KnowledgeChunkRepository chunkRepository
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }

    @Transactional
    public KnowledgeDocument ingest(
            String title,
            String docType,
            String sourceUri,
            String content,
            LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo
    ) {
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "Document content cannot be empty");
        }

        String contentHash = calculateSha256(content);

        // Ingestion duplicate prevention: throw controlled business exception
        if (documentRepository.findByContentHash(contentHash).isPresent()) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "Duplicate document content detected");
        }

        LocalDateTime now = LocalDateTime.now();

        // 1. Create and save KnowledgeDocument
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocType(docType);
        document.setTitle(title);
        document.setSourceUri(sourceUri);
        document.setEffectiveFrom(effectiveFrom);
        document.setEffectiveTo(effectiveTo);
        document.setStatus("ACTIVE");
        document.setContentHash(contentHash);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);

        try {
            documentRepository.save(document);
        } catch (DuplicateKeyException ex) {
            throw duplicateDocumentException();
        }

        // 2. Perform paragraph-based chunk splitting
        String[] paragraphs = content.split("\\r?\\n\\r?\\n");
        List<KnowledgeChunk> chunks = new ArrayList<>();
        int chunkNo = 0;

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocumentId(document.getId());
            chunk.setChunkNo(chunkNo++);
            chunk.setTitle(title);
            chunk.setContent(trimmed);
            chunk.setKeywords(null); // Optional full-text keywords field
            chunk.setSourceUri(sourceUri);
            chunk.setCreatedAt(now);
            chunk.setUpdatedAt(now);

            chunks.add(chunk);
        }

        if (chunks.isEmpty()) {
            throw new BusinessException(ResultCode.VALIDATION_FAILED, "No valid paragraphs found in document content");
        }

        // 3. Save chunks in batch
        chunkRepository.saveBatch(chunks);

        return document;
    }

    private String calculateSha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate SHA-256 hash of content", e);
        }
    }

    private BusinessException duplicateDocumentException() {
        return new BusinessException(ResultCode.VALIDATION_FAILED, "Duplicate document content detected");
    }
}
