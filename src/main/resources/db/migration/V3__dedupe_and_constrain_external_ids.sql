DELETE FROM manhwa_external_ids m
WHERE EXISTS (
    SELECT 1
    FROM manhwa_external_ids d
    WHERE d.manhwa_id = m.manhwa_id
      AND d.source = m.source
      AND d.id > m.id
);

ALTER TABLE manhwa_external_ids
    ADD CONSTRAINT uk_external_id_manhwa_source UNIQUE (manhwa_id, source);
