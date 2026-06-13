CREATE TABLE IF NOT EXISTS agent_trace_runs (
    id BIGINT PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    session_id BIGINT NOT NULL,
    workflow_id BIGINT NULL,
    user_id BIGINT NULL,
    root_input_event_id BIGINT NULL,
    root_output_event_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    provider VARCHAR(32) NULL,
    model VARCHAR(128) NULL,
    prompt_version VARCHAR(64) NULL,
    dry_run BOOLEAN NOT NULL DEFAULT FALSE,
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    duration_ms BIGINT NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(512) NULL,
    metadata_json TEXT NULL,
    UNIQUE KEY uk_agent_trace_runs_trace_id (trace_id),
    INDEX idx_agent_trace_runs_session_started (session_id, started_at),
    INDEX idx_agent_trace_runs_workflow (workflow_id)
);

CREATE TABLE IF NOT EXISTS agent_trace_spans (
    id BIGINT PRIMARY KEY,
    span_id VARCHAR(64) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    parent_span_id VARCHAR(64) NULL,
    span_type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    duration_ms BIGINT NULL,
    input_ref VARCHAR(256) NULL,
    output_ref VARCHAR(256) NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(512) NULL,
    metadata_json TEXT NULL,
    UNIQUE KEY uk_agent_trace_spans_span_id (span_id),
    INDEX idx_agent_trace_spans_trace_started (trace_id, started_at),
    INDEX idx_agent_trace_spans_parent (parent_span_id),
    INDEX idx_agent_trace_spans_type (span_type)
);

CREATE TABLE IF NOT EXISTS agent_trace_events (
    id BIGINT PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    span_id VARCHAR(64) NULL,
    event_type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    metadata_json TEXT NULL,
    occurred_at DATETIME(3) NOT NULL,
    INDEX idx_agent_trace_events_trace_occurred (trace_id, occurred_at),
    INDEX idx_agent_trace_events_span (span_id)
);

CREATE TABLE IF NOT EXISTS agent_trace_snapshots (
    id BIGINT PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    span_id VARCHAR(64) NULL,
    snapshot_type VARCHAR(64) NOT NULL,
    ref_type VARCHAR(64) NULL,
    ref_id VARCHAR(128) NULL,
    payload_json LONGTEXT NULL,
    payload_hash VARCHAR(64) NULL,
    redaction_summary_json TEXT NULL,
    created_at DATETIME(3) NOT NULL,
    INDEX idx_agent_trace_snapshots_trace_created (trace_id, created_at),
    INDEX idx_agent_trace_snapshots_span (span_id),
    INDEX idx_agent_trace_snapshots_ref (ref_type, ref_id)
);

ALTER TABLE agent_runs ADD COLUMN trace_id VARCHAR(64) NULL, ADD COLUMN span_id VARCHAR(64) NULL;
CREATE INDEX idx_agent_runs_trace_span ON agent_runs(trace_id, span_id);
ALTER TABLE tool_calls ADD COLUMN trace_id VARCHAR(64) NULL, ADD COLUMN span_id VARCHAR(64) NULL;
CREATE INDEX idx_tool_calls_trace_span ON tool_calls(trace_id, span_id);
ALTER TABLE workflow_steps ADD COLUMN trace_id VARCHAR(64) NULL, ADD COLUMN span_id VARCHAR(64) NULL;
CREATE INDEX idx_workflow_steps_trace_span ON workflow_steps(trace_id, span_id);
ALTER TABLE audit_logs ADD COLUMN trace_id VARCHAR(64) NULL, ADD COLUMN span_id VARCHAR(64) NULL;
CREATE INDEX idx_audit_logs_trace_span ON audit_logs(trace_id, span_id);
