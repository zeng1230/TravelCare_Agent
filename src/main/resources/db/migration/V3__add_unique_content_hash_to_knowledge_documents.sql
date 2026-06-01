ALTER TABLE knowledge_documents
ADD CONSTRAINT uk_knowledge_documents_content_hash UNIQUE (content_hash);
