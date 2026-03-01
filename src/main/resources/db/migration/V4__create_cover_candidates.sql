CREATE TABLE manhwa_cover_candidates (
    id BIGSERIAL PRIMARY KEY,
    manhwa_id BIGINT NOT NULL,
    source VARCHAR(32) NOT NULL,
    image_url TEXT NOT NULL,
    quality_score INTEGER NOT NULL,
    width INTEGER,
    height INTEGER,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_cover_candidate_manhwa_source
        UNIQUE (manhwa_id, source),
    CONSTRAINT fk_cover_candidate_manhwa
        FOREIGN KEY (manhwa_id) REFERENCES manhwas(id) ON DELETE CASCADE
);

CREATE INDEX idx_cover_candidates_manhwa_id
    ON manhwa_cover_candidates (manhwa_id);
