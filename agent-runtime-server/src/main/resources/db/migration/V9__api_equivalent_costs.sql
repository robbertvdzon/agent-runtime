-- Public API list prices used for both real API costs and the API-equivalent
-- estimate of subscription jobs. The task dimension is retained because it is
-- part of the existing immutable price-rate contract, even though token prices
-- currently do not differ between these two task types.
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
        ('o54', 'openai', 'gpt-5.4', 2.50, 0.25, 15.00, CAST('2026-03-05T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://developers.openai.com/api/docs/models/gpt-5.4'),
        ('o55', 'openai', 'gpt-5.5', 5.00, 0.50, 30.00, CAST('2026-04-23T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://developers.openai.com/api/docs/models/gpt-5.5'),
        ('o54m', 'openai', 'gpt-5.4-mini', 0.75, 0.075, 4.50, CAST('2026-03-17T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://developers.openai.com/api/docs/models/gpt-5.4-mini'),
        ('o56t', 'openai', 'gpt-5.6-terra', 2.00, 0.20, 12.00, CAST('2026-09-01T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://developers.openai.com/api/docs/models/gpt-5.6-terra'),
        ('o53s', 'openai', 'gpt-5.3-codex-spark', 1.75, 0.175, 14.00, CAST('2026-01-01T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'Proxy: public gpt-5.3-codex API rate; no separate public API rate exists for gpt-5.3-codex-spark. https://developers.openai.com/api/docs/models/gpt-5.3-codex'),
        ('o56s', 'openai', 'gpt-5.6-sol', 4.00, 0.40, 20.00, CAST('2026-09-01T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://developers.openai.com/api/docs/models/gpt-5.6-sol'),
        ('o56l', 'openai', 'gpt-5.6-luna', 0.20, 0.02, 1.20, CAST('2026-09-01T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://developers.openai.com/api/docs/models/gpt-5.6-luna'),
        ('as5', 'anthropic', 'claude-sonnet-5', 2.00, 0.20, 10.00, CAST('2026-08-10T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://www.anthropic.com/claude/sonnet'),
        ('ah45', 'anthropic', 'claude-haiku-4-5-20251001', 1.00, 0.10, 5.00, CAST('2026-01-01T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://platform.claude.com/docs/en/about-claude/pricing'),
        ('ao48', 'anthropic', 'claude-opus-4-8', 5.00, 0.50, 25.00, CAST('2026-01-01T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://platform.claude.com/docs/en/about-claude/pricing'),
        ('ao47', 'anthropic', 'claude-opus-4-7', 5.00, 0.50, 25.00, CAST('2026-01-01T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://platform.claude.com/docs/en/about-claude/pricing'),
        ('ao46', 'anthropic', 'claude-opus-4-6', 5.00, 0.50, 25.00, CAST('2026-01-01T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://platform.claude.com/docs/en/about-claude/pricing'),
        ('ao5', 'anthropic', 'claude-opus-5', 5.00, 0.50, 25.00, CAST('2026-09-01T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://platform.claude.com/docs/en/about-claude/pricing'),
        ('af5', 'anthropic', 'claude-fable-5', 10.00, 1.00, 50.00, CAST('2026-01-01T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://platform.claude.com/docs/en/about-claude/pricing'),
        ('as46', 'anthropic', 'claude-sonnet-4-6', 3.00, 0.30, 15.00, CAST('2026-02-17T00:00:00Z' AS TIMESTAMP WITH TIME ZONE), 'https://platform.claude.com/docs/en/models/sonnet-4-6/overview')
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

-- Existing v2 usage is repriced once so the monitor immediately shows the same
-- result for historical and future jobs. Existing calculated rows are retained.
INSERT INTO runtime_v2_cost (
    job_id, attempt_id, usage_id, price_rate_id, cost_kind, cost_status,
    amount, currency, created_at
)
SELECT
    usage.job_id,
    usage.attempt_id,
    usage.id,
    rate.id,
    CASE job.execution_mode
        WHEN 'SUBSCRIPTION' THEN 'API_EQUIVALENT'
        ELSE 'CALCULATED'
    END,
    'ESTIMATED',
    (usage.quantity / rate.unit_size) * rate.unit_price,
    rate.currency,
    CURRENT_TIMESTAMP
FROM runtime_v2_usage usage
JOIN runtime_v2_job job ON job.id = usage.job_id
JOIN runtime_v2_price_rate rate
  ON rate.vendor_id = job.vendor_id
 AND rate.model = job.model
 AND rate.execution_mode = 'API'
 AND rate.task_type = job.task_type
 AND rate.metric = usage.metric
 AND rate.valid_from <= usage.observed_at
 AND (rate.valid_until IS NULL OR rate.valid_until > usage.observed_at)
WHERE job.execution_mode IN ('API', 'SUBSCRIPTION')
  AND rate.version_number = (
      SELECT MAX(candidate.version_number)
      FROM runtime_v2_price_rate candidate
      WHERE candidate.vendor_id = rate.vendor_id
        AND candidate.model = rate.model
        AND candidate.execution_mode = rate.execution_mode
        AND candidate.task_type = rate.task_type
        AND candidate.metric = rate.metric
        AND candidate.valid_from <= usage.observed_at
        AND (candidate.valid_until IS NULL OR candidate.valid_until > usage.observed_at)
  )
  AND NOT EXISTS (
      SELECT 1
      FROM runtime_v2_cost existing_cost
      WHERE existing_cost.usage_id = usage.id
        AND existing_cost.cost_kind = CASE job.execution_mode
            WHEN 'SUBSCRIPTION' THEN 'API_EQUIVALENT'
            ELSE 'CALCULATED'
        END
  );
