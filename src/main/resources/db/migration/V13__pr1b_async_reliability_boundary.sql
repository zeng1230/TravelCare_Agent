CREATE TABLE IF NOT EXISTS outbox_events (
    id BIGINT PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    routing_key VARCHAR(128) NOT NULL,
    payload_json TEXT NOT NULL,
    payload_version VARCHAR(16) NOT NULL DEFAULT 'v1',
    dedupe_key VARCHAR(192) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME NULL,
    last_error_code VARCHAR(64) NULL,
    trace_id VARCHAR(64) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    published_at DATETIME NULL,
    UNIQUE KEY uk_outbox_events_dedupe_key (dedupe_key),
    INDEX idx_outbox_events_status_next_retry (status, next_retry_at),
    INDEX idx_outbox_events_aggregate (aggregate_type, aggregate_id),
    INDEX idx_outbox_events_trace (trace_id)
);

CREATE TABLE IF NOT EXISTS reconciliation_jobs (
    id BIGINT PRIMARY KEY,
    source_type VARCHAR(64) NOT NULL,
    source_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    result_code VARCHAR(64) NULL,
    trace_id VARCHAR(64) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_reconciliation_jobs_source (source_type, source_id),
    INDEX idx_reconciliation_jobs_status (status),
    INDEX idx_reconciliation_jobs_trace (trace_id)
);

ALTER TABLE workflow_tasks
    ADD COLUMN last_skipped_reason VARCHAR(128) NULL,
    ADD COLUMN dead_letter_reason VARCHAR(128) NULL,
    ADD COLUMN last_outbox_event_id BIGINT NULL;

ALTER TABLE tool_calls
    ADD COLUMN reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN last_error_code VARCHAR(64) NULL,
    ADD COLUMN updated_at DATETIME NULL;
