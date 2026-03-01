CREATE INDEX IF NOT EXISTS idx_metric_snapshots_metric_source_manhwa_captured
    ON metric_snapshots (metric_type, source_id, manhwa_id, captured_at DESC);

