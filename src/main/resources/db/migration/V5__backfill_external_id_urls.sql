UPDATE manhwa_external_ids
SET url = 'https://tapas.io/series/' || external_id
WHERE source = 'TAPAS'
  AND (url IS NULL OR url = '')
  AND external_id ~ '^[0-9]+$';

UPDATE manhwa_external_ids
SET url = external_id
WHERE source IN ('ASURA', 'WEBTOONS')
  AND (url IS NULL OR url = '')
  AND external_id LIKE 'http%';
