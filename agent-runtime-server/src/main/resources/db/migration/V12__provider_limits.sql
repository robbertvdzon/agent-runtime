ALTER TABLE runtime_v2_event ADD COLUMN provider_limit_json TEXT;
ALTER TABLE runtime_v2_worker ADD COLUMN provider_concurrency_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE runtime_v2_mock_fixture ADD COLUMN provider_limits_json TEXT NOT NULL DEFAULT '[]';
CREATE INDEX runtime_v2_attempt_worker_vendor ON runtime_v2_attempt(worker_id, worker_boot_id, status, vendor_id);
