CREATE TABLE manhwa_titles (
    id BIGSERIAL PRIMARY KEY,
    manhwa_id BIGINT NOT NULL,
    title TEXT NOT NULL,
    normalized_title TEXT NOT NULL,
    source VARCHAR(32) NOT NULL,
    language VARCHAR(32),
    canonical BOOLEAN NOT NULL DEFAULT FALSE,
    confidence INTEGER,
    CONSTRAINT uk_manhwa_titles_identity
        UNIQUE (manhwa_id, normalized_title, source, language),
    CONSTRAINT fk_manhwa_titles_manhwa
        FOREIGN KEY (manhwa_id) REFERENCES manhwas(id) ON DELETE CASCADE
);

CREATE INDEX idx_manhwa_titles_manhwa_id
    ON manhwa_titles (manhwa_id);

CREATE INDEX idx_manhwa_titles_normalized
    ON manhwa_titles (normalized_title);

CREATE INDEX idx_manhwa_titles_source
    ON manhwa_titles (source);

CREATE TABLE manhwa_external_ids (
    id BIGSERIAL PRIMARY KEY,
    manhwa_id BIGINT NOT NULL,
    source VARCHAR(32) NOT NULL,
    external_id TEXT NOT NULL,
    url TEXT,
    CONSTRAINT uk_external_id_source_value
        UNIQUE (source, external_id),
    CONSTRAINT fk_manhwa_external_ids_manhwa
        FOREIGN KEY (manhwa_id) REFERENCES manhwas(id) ON DELETE CASCADE
);

CREATE INDEX idx_external_ids_manhwa_id
    ON manhwa_external_ids (manhwa_id);
