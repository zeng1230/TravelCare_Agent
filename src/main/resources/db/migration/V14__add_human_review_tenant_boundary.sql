ALTER TABLE human_review_cases ADD COLUMN tenant_id VARCHAR(64) NULL AFTER id;

UPDATE human_review_cases hr
JOIN sessions s ON s.id = hr.session_id
SET hr.tenant_id = s.tenant_id;

ALTER TABLE human_review_cases MODIFY COLUMN tenant_id VARCHAR(64) NOT NULL;

CREATE INDEX idx_human_review_cases_tenant_status
    ON human_review_cases (tenant_id, status);
