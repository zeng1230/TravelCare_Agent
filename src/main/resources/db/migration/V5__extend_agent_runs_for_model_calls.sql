ALTER TABLE agent_runs
    ADD COLUMN provider VARCHAR(32) NULL AFTER source,
    ADD COLUMN model VARCHAR(128) NULL AFTER provider,
    ADD COLUMN request_json TEXT NULL AFTER model,
    ADD COLUMN response_json TEXT NULL AFTER request_json,
    ADD COLUMN input_tokens INT NULL AFTER response_json,
    ADD COLUMN output_tokens INT NULL AFTER input_tokens;

CREATE INDEX idx_agent_runs_provider_created ON agent_runs(provider, created_at);
