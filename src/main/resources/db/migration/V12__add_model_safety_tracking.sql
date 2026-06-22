ALTER TABLE agent_runs
    ADD COLUMN provider_status VARCHAR(32) NULL AFTER fallback_used,
    ADD COLUMN safety_decision VARCHAR(32) NULL AFTER provider_status,
    ADD COLUMN safety_reason_code VARCHAR(64) NULL AFTER safety_decision,
    ADD COLUMN risk_flags_json TEXT NULL AFTER safety_reason_code;
