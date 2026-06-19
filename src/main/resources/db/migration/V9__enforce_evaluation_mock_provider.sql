ALTER TABLE evaluation_runs
    ADD CONSTRAINT chk_evaluation_runs_provider_mode CHECK (provider_mode = 'mock');
