CREATE INDEX runtime_v2_attempt_usage_period ON runtime_v2_attempt (started_at, execution_mode);
CREATE INDEX runtime_v2_cost_attempt ON runtime_v2_cost (attempt_id);
