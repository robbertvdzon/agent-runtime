ALTER TABLE runtime_v2_worker
    ADD COLUMN repository_aliases_json TEXT NOT NULL DEFAULT '[]';

ALTER TABLE runtime_v2_job
    ADD COLUMN repository_result_json TEXT;

ALTER TABLE runtime_v2_mock_fixture
    ADD COLUMN repository_result_json TEXT;

CREATE TABLE runtime_v2_repository_publication (
    job_id VARCHAR(36) PRIMARY KEY REFERENCES runtime_v2_job(id),
    attempt_id VARCHAR(36) NOT NULL REFERENCES runtime_v2_attempt(id),
    alias VARCHAR(100) NOT NULL,
    branch_name VARCHAR(240) NOT NULL,
    checkout_commit_sha VARCHAR(40) NOT NULL,
    intended_commit_sha VARCHAR(40) NOT NULL,
    diff_stat VARCHAR(20000),
    result_json TEXT NOT NULL,
    output_object_ids_json TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    prepared_at TIMESTAMP WITH TIME ZONE NOT NULL,
    pushed_at TIMESTAMP WITH TIME ZONE,
    finalized_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX runtime_v2_repository_publication_attempt_idx
    ON runtime_v2_repository_publication(attempt_id, status);
