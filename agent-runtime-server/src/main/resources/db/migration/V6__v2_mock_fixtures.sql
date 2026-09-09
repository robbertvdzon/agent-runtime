CREATE TABLE runtime_v2_mock_fixture (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    result_json TEXT,
    output_sequence_json TEXT,
    error_code VARCHAR(120),
    error_message VARCHAR(2000),
    delay_millis BIGINT NOT NULL DEFAULT 0,
    output_artifact_names_json TEXT NOT NULL DEFAULT '[]',
    consumed_by_job_id VARCHAR(36) REFERENCES runtime_v2_job(id),
    consumed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT runtime_v2_mock_fixture_target UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT runtime_v2_mock_fixture_response CHECK (
        (CASE WHEN result_json IS NULL THEN 0 ELSE 1 END) +
        (CASE WHEN output_sequence_json IS NULL THEN 0 ELSE 1 END) +
        (CASE WHEN error_code IS NULL THEN 0 ELSE 1 END) = 1
    )
);

CREATE INDEX runtime_v2_mock_fixture_lookup_idx
    ON runtime_v2_mock_fixture(tenant_id, idempotency_key, consumed_by_job_id);
