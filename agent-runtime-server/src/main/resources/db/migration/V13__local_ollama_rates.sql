-- Local inference has no external provider token charge; hardware/electricity are excluded.
INSERT INTO runtime_v2_price_rate (
    id, version_number, vendor_id, model, execution_mode, task_type, metric,
    unit_size, unit_price, currency, valid_from, valid_until, source_reference, created_at
)
SELECT 'ollama-qwen35-35b-' || m.code, 1, 'ollama', 'qwen3.5:35b', 'API',
    'STRUCTURED_GENERATION', m.metric, 1000000, 0, 'USD',
    CAST('2026-10-25T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), NULL,
    'Local Ollama inference; external provider token charge only, excludes hardware and electricity', CURRENT_TIMESTAMP
FROM (VALUES ('in', 'INPUT_TOKENS'), ('cin', 'CACHED_INPUT_TOKENS'), ('out', 'OUTPUT_TOKENS')) AS m(code, metric);
