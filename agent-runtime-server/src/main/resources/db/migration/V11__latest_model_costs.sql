-- Public API list prices for the current OpenAI GPT-6 and Anthropic Claude 5.5 models.
-- These prices are also used as the API-equivalent estimate for subscription jobs.
INSERT INTO runtime_v2_price_rate (
    id, version_number, vendor_id, model, execution_mode, task_type, metric,
    unit_size, unit_price, currency, valid_from, valid_until, source_reference, created_at
)
SELECT
    'api-' || model_rate.code || '-' || task.code || '-' || metric.code,
    1,
    model_rate.vendor_id,
    model_rate.model,
    'API',
    task.task_type,
    metric.metric,
    1000000,
    CASE metric.metric
        WHEN 'INPUT_TOKENS' THEN model_rate.input_price
        WHEN 'CACHED_INPUT_TOKENS' THEN model_rate.cached_input_price
        WHEN 'OUTPUT_TOKENS' THEN model_rate.output_price
    END,
    'USD',
    model_rate.valid_from,
    NULL,
    model_rate.source_reference,
    CURRENT_TIMESTAMP
FROM (
    VALUES
        ('o6a', 'openai', 'gpt-6-astra', 10.00, 1.00, 50.00, CAST('2026-09-30T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://developers.openai.com/api/docs/models/gpt-6-astra'),
        ('o6s', 'openai', 'gpt-6-sol', 2.00, 0.20, 10.00, CAST('2026-09-30T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://developers.openai.com/api/docs/models/gpt-6-sol'),
        ('o6l', 'openai', 'gpt-6-luna', 0.10, 0.01, 0.50, CAST('2026-09-30T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://developers.openai.com/api/docs/models/gpt-6-luna'),
        ('ao55', 'anthropic', 'claude-opus-5-5', 4.00, 0.40, 20.00, CAST('2026-09-30T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://platform.claude.com/docs/en/models/opus-5-5/overview'),
        ('as55', 'anthropic', 'claude-sonnet-5-5', 2.00, 0.20, 10.00, CAST('2026-09-30T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://platform.claude.com/docs/en/models/sonnet-5-5/overview')
) AS model_rate(code, vendor_id, model, input_price, cached_input_price, output_price, valid_from, source_reference)
CROSS JOIN (
    VALUES ('sg', 'STRUCTURED_GENERATION'), ('ra', 'REPOSITORY_AGENT')
) AS task(code, task_type)
CROSS JOIN (
    VALUES ('in', 'INPUT_TOKENS'), ('cin', 'CACHED_INPUT_TOKENS'), ('out', 'OUTPUT_TOKENS')
) AS metric(code, metric)
WHERE NOT EXISTS (
    SELECT 1
    FROM runtime_v2_price_rate existing
    WHERE existing.vendor_id = model_rate.vendor_id
      AND existing.model = model_rate.model
      AND existing.execution_mode = 'API'
      AND existing.task_type = task.task_type
      AND existing.metric = metric.metric
      AND existing.version_number = 1
);
