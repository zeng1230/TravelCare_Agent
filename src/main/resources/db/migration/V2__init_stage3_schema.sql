CREATE TABLE IF NOT EXISTS knowledge_documents (
    id BIGINT PRIMARY KEY,
    doc_type VARCHAR(64) NOT NULL,
    title VARCHAR(256) NOT NULL,
    source_uri VARCHAR(512) NULL,
    effective_from DATETIME NULL,
    effective_to DATETIME NULL,
    status VARCHAR(32) NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE INDEX idx_knowledge_documents_type_status
ON knowledge_documents(doc_type, status);

CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id BIGINT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    chunk_no INT NOT NULL,
    title VARCHAR(256) NOT NULL,
    content TEXT NOT NULL,
    keywords VARCHAR(512) NULL,
    source_uri VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE INDEX idx_knowledge_chunks_document_id
ON knowledge_chunks(document_id);

CREATE FULLTEXT INDEX ft_knowledge_chunks_content
ON knowledge_chunks(title, content, keywords);

CREATE TABLE IF NOT EXISTS agent_memories (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id BIGINT NULL,
    workflow_id BIGINT NULL,
    memory_type VARCHAR(64) NOT NULL,
    memory_key VARCHAR(128) NOT NULL,
    memory_value TEXT NOT NULL,
    confidence DECIMAL(5,4) NOT NULL,
    source_event_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE INDEX idx_agent_memories_user_type
ON agent_memories(user_id, memory_type, status);

CREATE INDEX idx_agent_memories_workflow
ON agent_memories(workflow_id);
