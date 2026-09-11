ALTER TABLE runtime_v2_job
    ADD COLUMN verification_result_json TEXT;

ALTER TABLE runtime_v2_mock_fixture
    ADD COLUMN verification_result_json TEXT;

ALTER TABLE runtime_v2_repository_publication
    ADD COLUMN verification_result_json TEXT;
