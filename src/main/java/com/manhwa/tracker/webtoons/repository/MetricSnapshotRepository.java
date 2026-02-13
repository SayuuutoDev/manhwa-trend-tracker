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
                   m.cover_image_url AS coverImageUrl,
                   l.metric_value AS latestValue,
                   l.captured_at AS latestAt,
                   COALESCE(p.metric_value, o.metric_value) AS previousValue,
                   COALESCE(p.captured_at, o.captured_at) AS previousAt,
                   (l.metric_value - COALESCE(p.metric_value, o.metric_value)) AS growth
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
                SELECT ms.metric_value, ms.captured_at
                FROM metric_snapshots ms
                WHERE ms.manhwa_id = m.id
                  AND ms.metric_type = :metricType
                  AND (:sourceId IS NULL OR ms.source_id = :sourceId)
                  AND ms.captured_at <= l.captured_at - INTERVAL '7 days'
                ORDER BY ms.captured_at DESC
                LIMIT 1
            ) p ON TRUE
            LEFT JOIN LATERAL (
                SELECT ms.metric_value, ms.captured_at
                FROM metric_snapshots ms
                WHERE ms.manhwa_id = m.id
                  AND ms.metric_type = :metricType
                  AND (:sourceId IS NULL OR ms.source_id = :sourceId)
                ORDER BY ms.captured_at ASC
                LIMIT 1
            ) o ON TRUE
            WHERE COALESCE(p.metric_value, o.metric_value) IS NOT NULL
            ORDER BY growth DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<TrendingProjection> findTrending(
            @Param("metricType") String metricType,
            @Param("sourceId") Integer sourceId,
            @Param("limit") int limit
    );
}
