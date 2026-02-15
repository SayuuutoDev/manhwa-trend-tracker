package com.manhwa.tracker.webtoons.repository;

import com.manhwa.tracker.webtoons.model.MetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, Long> {
    @Query(value = """
            SELECT m.id AS manhwaId,
                   m.canonical_title AS title,
                   COALESCE(NULLIF(m.cover_image_url, ''), '/images/cover-fallback.svg') AS coverImageUrl,
                   r.read_url AS readUrl,
                   l.metric_value AS latestValue,
                   l.captured_at AS latestAt,
                   p.metric_value AS previousValue,
                   p.captured_at AS previousAt,
                   (l.metric_value - p.metric_value) AS growth,
                   EXTRACT(EPOCH FROM (l.captured_at - p.captured_at)) / 86400.0 AS baselineDays,
                   (l.metric_value - p.metric_value)
                       / NULLIF(EXTRACT(EPOCH FROM (l.captured_at - p.captured_at)) / 86400.0, 0) AS growthPerDay,
                   CASE
                       WHEN p.metric_value > 0
                           THEN (l.metric_value - p.metric_value)::numeric / p.metric_value::numeric
                       ELSE NULL
                   END AS growthPercent
            FROM manhwas m
            JOIN LATERAL (
                SELECT ms.metric_value, ms.captured_at
                FROM metric_snapshots ms
                WHERE ms.manhwa_id = m.id
                  AND ms.metric_type = :metricType
                  AND (:sourceId IS NULL OR ms.source_id = :sourceId)
                ORDER BY ms.captured_at DESC
                LIMIT 1
            ) l ON TRUE
            JOIN LATERAL (
                SELECT ms.metric_value, ms.captured_at
                FROM metric_snapshots ms
                WHERE ms.manhwa_id = m.id
                  AND ms.metric_type = :metricType
                  AND (:sourceId IS NULL OR ms.source_id = :sourceId)
                  AND ms.captured_at < l.captured_at
                ORDER BY ABS(EXTRACT(EPOCH FROM ((l.captured_at - INTERVAL '7 days') - ms.captured_at))) ASC,
                         ms.captured_at DESC
                LIMIT 1
            ) p ON TRUE
            LEFT JOIN LATERAL (
                SELECT COALESCE(NULLIF(mei.url, ''), CASE WHEN mei.external_id LIKE 'http%' THEN mei.external_id ELSE NULL END) AS read_url
                FROM manhwa_external_ids mei
                WHERE mei.manhwa_id = m.id
                  AND (
                      (:sourceId = 1 AND mei.source = 'WEBTOONS')
                      OR (:sourceId = 2 AND mei.source = 'ASURA')
                      OR (:sourceId = 3 AND mei.source = 'TAPAS')
                      OR (
                          :sourceId IS NULL
                          AND mei.source IN ('WEBTOONS', 'ASURA', 'TAPAS')
                      )
                  )
                ORDER BY CASE mei.source
                             WHEN 'WEBTOONS' THEN 1
                             WHEN 'ASURA' THEN 2
                             WHEN 'TAPAS' THEN 3
                             ELSE 99
                         END
                LIMIT 1
            ) r ON TRUE
            WHERE l.captured_at >= NOW() - INTERVAL '3 days'
              AND (
                  m.genre IS NULL
                  OR :excludedGenresRegex IS NULL
                  OR :excludedGenresRegex = ''
                  OR m.genre !~* :excludedGenresRegex
              )
              AND EXTRACT(EPOCH FROM (l.captured_at - p.captured_at)) >= 21600
            ORDER BY CASE
                         WHEN :rankingMode = 'ABS' THEN (l.metric_value - p.metric_value)::numeric
                         WHEN :rankingMode = 'PCT' THEN
                             CASE
                                 WHEN p.metric_value > 0
                                     THEN (l.metric_value - p.metric_value)::numeric / p.metric_value::numeric
                                 ELSE NULL
                             END
                         ELSE (l.metric_value - p.metric_value)
                                  / NULLIF(EXTRACT(EPOCH FROM (l.captured_at - p.captured_at)) / 86400.0, 0)
                     END DESC,
                     (l.metric_value - p.metric_value) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<TrendingProjection> findTrending(
            @Param("metricType") String metricType,
            @Param("sourceId") Integer sourceId,
            @Param("limit") int limit,
            @Param("rankingMode") String rankingMode,
            @Param("excludedGenresRegex") String excludedGenresRegex
    );
}
