ALTER TABLE runtime_job DROP COLUMN job_profile;
ALTER TABLE runtime_job DROP COLUMN job_key;

ALTER TABLE runtime_mock_response DROP COLUMN job_profile;
ALTER TABLE runtime_mock_response DROP COLUMN job_key;
ALTER TABLE runtime_mock_response DROP COLUMN consumer_correlation;
