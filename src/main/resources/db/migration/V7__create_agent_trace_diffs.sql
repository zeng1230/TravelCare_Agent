CREATE TABLE IF NOT EXISTS agent_trace_diffs (
    id BIGINT PRIMARY KEY,
    original_trace_id VARCHAR(64) NOT NULL,
    dry_run_trace_id VARCHAR(64) NOT NULL,
    changed BOOLEAN NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    changed_fields_json LONGTEXT NULL,
    original_summary_json LONGTEXT NULL,
    dry_run_summary_json LONGTEXT NULL,
    explanation TEXT NULL,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_agent_trace_diffs_pair (original_trace_id, dry_run_trace_id),
    INDEX idx_agent_trace_diffs_dry_run (dry_run_trace_id),
    INDEX idx_agent_trace_diffs_risk_created (risk_level, created_at)
);
