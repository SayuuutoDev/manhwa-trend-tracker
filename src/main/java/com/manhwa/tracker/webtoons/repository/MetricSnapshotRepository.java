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
                   END AS growthPercent,
                   CASE
                       WHEN :rankingMode = 'ABS' THEN (l.metric_value - p.metric_value)::numeric
                       WHEN :rankingMode = 'PCT' THEN
                           CASE
                               WHEN p.metric_value > 0
                                   THEN (l.metric_value - p.metric_value)::numeric / p.metric_value::numeric
                               ELSE NULL
                           END
                       ELSE (l.metric_value - p.metric_value)
                                / NULLIF(EXTRACT(EPOCH FROM (l.captured_at - p.captured_at)) / 86400.0, 0)
                   END AS rankingScore
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
              AND (:minPreviousValue IS NULL OR p.metric_value >= :minPreviousValue)
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
    List<TrendingProjection> findTrendingGrowth(
            @Param("metricType") String metricType,
            @Param("sourceId") Integer sourceId,
            @Param("limit") int limit,
            @Param("rankingMode") String rankingMode,
            @Param("excludedGenresRegex") String excludedGenresRegex,
            @Param("minPreviousValue") Long minPreviousValue
    );

    @Query(value = """
            SELECT m.id AS manhwaId,
                   m.canonical_title AS title,
                   COALESCE(NULLIF(m.cover_image_url, ''), '/images/cover-fallback.svg') AS coverImageUrl,
                   r.read_url AS readUrl,
                   l.metric_value AS latestValue,
                   l.captured_at AS latestAt,
                   NULL::bigint AS previousValue,
                   NULL::timestamp AS previousAt,
                   NULL::bigint AS growth,
                   NULL::double precision AS baselineDays,
                   NULL::double precision AS growthPerDay,
                   NULL::double precision AS growthPercent,
                   l.metric_value::double precision AS rankingScore
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
            ORDER BY l.metric_value DESC, l.captured_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<TrendingProjection> findTrendingTotal(
            @Param("metricType") String metricType,
            @Param("sourceId") Integer sourceId,
            @Param("limit") int limit,
            @Param("excludedGenresRegex") String excludedGenresRegex
    );

    @Query(value = """
            SELECT m.id AS manhwaId,
                   m.canonical_title AS title,
                   COALESCE(NULLIF(m.cover_image_url, ''), '/images/cover-fallback.svg') AS coverImageUrl,
                   r.read_url AS readUrl,
                   n.metric_value AS latestValue,
                   n.captured_at AS latestAt,
                   d.metric_value AS previousValue,
                   d.captured_at AS previousAt,
                   NULL::bigint AS growth,
                   ABS(EXTRACT(EPOCH FROM (n.captured_at - d.captured_at))) / 86400.0 AS baselineDays,
                   NULL::double precision AS growthPerDay,
                   (n.metric_value::numeric / NULLIF(d.metric_value::numeric, 0))::double precision AS growthPercent,
                   (n.metric_value::numeric / NULLIF(d.metric_value::numeric, 0))::double precision AS rankingScore
            FROM manhwas m
            JOIN LATERAL (
                SELECT ms.metric_value, ms.captured_at
                FROM metric_snapshots ms
                WHERE ms.manhwa_id = m.id
                  AND ms.metric_type = :numeratorMetricType
                  AND (:sourceId IS NULL OR ms.source_id = :sourceId)
                ORDER BY ms.captured_at DESC
                LIMIT 1
            ) n ON TRUE
            JOIN LATERAL (
                SELECT ms.metric_value, ms.captured_at
                FROM metric_snapshots ms
                WHERE ms.manhwa_id = m.id
                  AND ms.metric_type = :denominatorMetricType
                  AND (:sourceId IS NULL OR ms.source_id = :sourceId)
                ORDER BY ms.captured_at DESC
                LIMIT 1
            ) d ON TRUE
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
            WHERE n.captured_at >= NOW() - INTERVAL '3 days'
              AND d.captured_at >= NOW() - INTERVAL '3 days'
              AND d.metric_value > 0
              AND (
                  m.genre IS NULL
                  OR :excludedGenresRegex IS NULL
                  OR :excludedGenresRegex = ''
                  OR m.genre !~* :excludedGenresRegex
              )
            ORDER BY (n.metric_value::numeric / NULLIF(d.metric_value::numeric, 0)) DESC,
                     n.metric_value DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<TrendingProjection> findTrendingEngagement(
            @Param("numeratorMetricType") String numeratorMetricType,
            @Param("denominatorMetricType") String denominatorMetricType,
            @Param("sourceId") Integer sourceId,
            @Param("limit") int limit,
            @Param("excludedGenresRegex") String excludedGenresRegex
    );

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
                   ((l.metric_value - p.metric_value)
                       / NULLIF(EXTRACT(EPOCH FROM (l.captured_at - p.captured_at)) / 86400.0, 0)) AS growthPerDay,
                   NULL::double precision AS growthPercent,
                   (
                       ((l.metric_value - p.metric_value)
                           / NULLIF(EXTRACT(EPOCH FROM (l.captured_at - p.captured_at)) / 86400.0, 0))
                       -
                       ((p.metric_value - q.metric_value)
                           / NULLIF(EXTRACT(EPOCH FROM (p.captured_at - q.captured_at)) / 86400.0, 0))
                   ) AS rankingScore
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
                  AND ms.captured_at <= l.captured_at - INTERVAL '1 hour'
                ORDER BY ms.captured_at DESC
                LIMIT 1
            ) p ON TRUE
            JOIN LATERAL (
                SELECT ms.metric_value, ms.captured_at
                FROM metric_snapshots ms
                WHERE ms.manhwa_id = m.id
                  AND ms.metric_type = :metricType
                  AND (:sourceId IS NULL OR ms.source_id = :sourceId)
                  AND ms.captured_at <= p.captured_at - INTERVAL '1 hour'
                ORDER BY ms.captured_at DESC
                LIMIT 1
            ) q ON TRUE
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
            ORDER BY rankingScore DESC, growth DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<TrendingProjection> findTrendingAcceleration(
            @Param("metricType") String metricType,
            @Param("sourceId") Integer sourceId,
            @Param("limit") int limit,
            @Param("excludedGenresRegex") String excludedGenresRegex
    );
}
