CREATE TABLE IF NOT EXISTS sessions (
    id BIGINT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_workflow_id BIGINT NULL,
    context_version BIGINT NOT NULL,
    locked_until DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_sessions_user_status (user_id, status),
    INDEX idx_sessions_locked_until (locked_until)
);

CREATE TABLE IF NOT EXISTS session_events (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    seq_no INT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    metadata_json TEXT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_session_seq (session_id, seq_no),
    INDEX idx_session_events_session_created (session_id, created_at)
);

CREATE TABLE IF NOT EXISTS workflows (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    workflow_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_step VARCHAR(64) NOT NULL,
    state_json TEXT NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_workflows_session_status (session_id, status),
    INDEX idx_workflows_type_status (workflow_type, status)
);

CREATE TABLE IF NOT EXISTS workflow_steps (
    id BIGINT PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    step_name VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_json TEXT NULL,
    output_json TEXT NULL,
    error_code VARCHAR(64) NULL,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    INDEX idx_workflow_steps_workflow_step (workflow_id, step_name),
    INDEX idx_workflow_steps_status (status)
);

CREATE TABLE IF NOT EXISTS tool_calls (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    workflow_id BIGINT NOT NULL,
    step_id BIGINT NULL,
    tool_name VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    request_json TEXT NOT NULL,
    response_json TEXT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    timeout_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_tool_calls_idempotency_key (idempotency_key),
    INDEX idx_tool_calls_workflow_tool (workflow_id, tool_name)
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    scope VARCHAR(64) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_type VARCHAR(64) NULL,
    result_id BIGINT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_idempotency_keys_expires_at (expires_at)
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    session_id BIGINT NULL,
    workflow_id BIGINT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id BIGINT NULL,
    before_json TEXT NULL,
    after_json TEXT NULL,
    evidence_json TEXT NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_audit_logs_target (target_type, target_id),
    INDEX idx_audit_logs_session_action (session_id, action)
);

CREATE TABLE IF NOT EXISTS refund_cases (
    id BIGINT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    workflow_id BIGINT NOT NULL,
    refund_no VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    refund_amount DECIMAL(10,2) NULL,
    reason VARCHAR(255) NOT NULL,
    policy_result_json TEXT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_refund_cases_refund_no (refund_no),
    INDEX idx_refund_cases_user_order (user_id, order_id),
    INDEX idx_refund_cases_workflow (workflow_id)
);

CREATE TABLE IF NOT EXISTS human_review_cases (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    workflow_id BIGINT NOT NULL,
    refund_case_id BIGINT NULL,
    case_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    priority VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    evidence_json TEXT NOT NULL,
    assigned_to VARCHAR(128) NULL,
    resolution VARCHAR(64) NULL,
    resolution_note VARCHAR(512) NULL,
    resolved_by VARCHAR(128) NULL,
    resolved_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_human_review_cases_session_id (session_id),
    INDEX idx_human_review_cases_workflow_id (workflow_id),
    INDEX idx_human_review_cases_status (status)
);

CREATE TABLE IF NOT EXISTS workflow_tasks (
    id BIGINT PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_json TEXT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    next_run_at DATETIME NULL,
    locked_by VARCHAR(128) NULL,
    locked_until DATETIME NULL,
    last_error_code VARCHAR(64) NULL,
    last_error_message VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_workflow_tasks_session_id (session_id),
    INDEX idx_workflow_tasks_workflow_id (workflow_id),
    INDEX idx_workflow_tasks_status_next_run (status, next_run_at),
    INDEX idx_workflow_tasks_locked_until (locked_until)
);
