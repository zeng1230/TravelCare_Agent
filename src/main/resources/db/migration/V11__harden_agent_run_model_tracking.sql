ALTER TABLE agent_runs
    MODIFY COLUMN session_id BIGINT NULL,
    DROP COLUMN request_json,
    DROP COLUMN response_json,
    ADD COLUMN provider_mode VARCHAR(32) NULL AFTER source,
    ADD COLUMN fallback_provider VARCHAR(32) NULL AFTER model,
    ADD COLUMN fallback_model VARCHAR(128) NULL AFTER fallback_provider,
    ADD COLUMN request_hash VARCHAR(64) NULL AFTER fallback_model,
    ADD COLUMN response_hash VARCHAR(64) NULL AFTER request_hash,
    ADD COLUMN total_tokens INT NULL AFTER output_tokens,
    ADD COLUMN fallback_used BOOLEAN NOT NULL DEFAULT FALSE AFTER total_tokens;

CREATE INDEX idx_agent_runs_provider_mode_created
    ON agent_runs(provider_mode, created_at);
