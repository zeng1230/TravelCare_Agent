CREATE TABLE evaluation_baselines (
    id BIGINT PRIMARY KEY,
    dataset_id BIGINT NOT NULL,
    dataset_key VARCHAR(128) NOT NULL,
    dataset_version INT NOT NULL,
    run_id BIGINT NOT NULL,
    promoted_by VARCHAR(128) NOT NULL,
    promoted_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    INDEX idx_evaluation_baselines_dataset (dataset_id),
    INDEX idx_evaluation_baselines_key_version (dataset_key, dataset_version),
    INDEX idx_evaluation_baselines_run (run_id),
    INDEX idx_evaluation_baselines_promoted (promoted_at)
);

ALTER TABLE evaluation_datasets
    ADD COLUMN current_baseline_run_id BIGINT NULL;

ALTER TABLE evaluation_runs
    ADD COLUMN regression_status VARCHAR(32) NOT NULL DEFAULT 'NOT_COMPARED',
    ADD COLUMN regression_count INT NOT NULL DEFAULT 0,
    ADD COLUMN improved_count INT NOT NULL DEFAULT 0,
    ADD COLUMN new_case_count INT NOT NULL DEFAULT 0,
    ADD COLUMN missing_case_count INT NOT NULL DEFAULT 0;

ALTER TABLE evaluation_case_results
    ADD COLUMN baseline_case_result_id BIGINT NULL,
    ADD COLUMN regression_status VARCHAR(32) NOT NULL DEFAULT 'NOT_COMPARED',
    ADD COLUMN regression_reason_json TEXT NULL;
