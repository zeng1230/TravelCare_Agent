CREATE TABLE evaluation_datasets (
    id BIGINT PRIMARY KEY, dataset_key VARCHAR(128) NOT NULL, name VARCHAR(255) NOT NULL,
    version INT NOT NULL, status VARCHAR(32) NOT NULL, description TEXT NULL,
    cloned_from_dataset_id BIGINT NULL, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_evaluation_datasets_key_version (dataset_key, version),
    INDEX idx_evaluation_datasets_status (status), INDEX idx_evaluation_datasets_key (dataset_key)
);
CREATE TABLE evaluation_cases (
    id BIGINT PRIMARY KEY, dataset_id BIGINT NOT NULL, case_key VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL, source_trace_id BIGINT NOT NULL, expectation_json TEXT NOT NULL,
    tags_json TEXT NULL, enabled TINYINT NOT NULL, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_evaluation_cases_dataset_key (dataset_id, case_key),
    INDEX idx_evaluation_cases_dataset (dataset_id), INDEX idx_evaluation_cases_source_trace (source_trace_id),
    INDEX idx_evaluation_cases_enabled (enabled)
);
CREATE TABLE evaluation_runs (
    id BIGINT PRIMARY KEY, dataset_id BIGINT NOT NULL, dataset_version INT NOT NULL,
    baseline_run_id BIGINT NULL, provider_mode VARCHAR(32) NOT NULL, prompt_stub_version VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL, total_count INT NOT NULL, passed_count INT NOT NULL,
    failed_count INT NOT NULL, error_count INT NOT NULL, skipped_count INT NOT NULL,
    config_json TEXT NOT NULL, summary_json TEXT NULL, started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL, created_at DATETIME(3) NOT NULL,
    INDEX idx_evaluation_runs_dataset (dataset_id), INDEX idx_evaluation_runs_status (status),
    INDEX idx_evaluation_runs_created (created_at)
);
CREATE TABLE evaluation_case_results (
    id BIGINT PRIMARY KEY, run_id BIGINT NOT NULL, case_id BIGINT NOT NULL, case_key VARCHAR(128) NOT NULL,
    source_trace_id BIGINT NOT NULL, dry_run_trace_id BIGINT NULL, diff_id BIGINT NULL,
    status VARCHAR(32) NOT NULL, scores_json TEXT NULL, failure_reason TEXT NULL,
    risk_level VARCHAR(32) NULL, started_at DATETIME(3) NULL, finished_at DATETIME(3) NULL,
    UNIQUE KEY uk_evaluation_case_results_run_case (run_id, case_id),
    INDEX idx_evaluation_case_results_run (run_id), INDEX idx_evaluation_case_results_case (case_id),
    INDEX idx_evaluation_case_results_key (case_key), INDEX idx_evaluation_case_results_status (status),
    INDEX idx_evaluation_case_results_risk (risk_level)
);
