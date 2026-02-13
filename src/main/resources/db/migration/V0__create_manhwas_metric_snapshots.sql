CREATE TABLE manhwas (
    id BIGSERIAL PRIMARY KEY,
    canonical_title TEXT NOT NULL UNIQUE,
    description TEXT,
    genre TEXT,
    cover_image_url TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE metric_snapshots (
    id BIGSERIAL PRIMARY KEY,
    manhwa_id BIGINT,
    source_id INTEGER,
    metric_type VARCHAR(32) NOT NULL,
    metric_value BIGINT NOT NULL,
    captured_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_metric_snapshots_manhwa_id
    ON metric_snapshots (manhwa_id);
