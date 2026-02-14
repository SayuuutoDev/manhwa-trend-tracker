package com.manhwa.tracker.webtoons.service;

import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.model.TrendingManhwaDTO;
import com.manhwa.tracker.webtoons.model.TrendingRankingMode;
import com.manhwa.tracker.webtoons.repository.MetricSnapshotRepository;
import com.manhwa.tracker.webtoons.repository.TrendingProjection;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TrendingService {
    private final MetricSnapshotRepository metricSnapshotRepository;

    public TrendingService(MetricSnapshotRepository metricSnapshotRepository) {
        this.metricSnapshotRepository = metricSnapshotRepository;
    }

    public List<TrendingManhwaDTO> getTrending(
            MetricType metricType,
            Integer sourceId,
            int limit,
            TrendingRankingMode rankingMode
    ) {
        List<TrendingProjection> rows = metricSnapshotRepository.findTrending(
                metricType.name(),
                sourceId,
                limit,
                rankingMode.name()
        );
        return rows.stream()
                .map(row -> new TrendingManhwaDTO(
                        row.getManhwaId(),
                        row.getTitle(),
                        metricType,
                        row.getCoverImageUrl(),
                        row.getReadUrl(),
                        row.getLatestValue(),
                        row.getLatestAt(),
                        row.getPreviousValue(),
                        row.getPreviousAt(),
                        row.getGrowth(),
                        row.getBaselineDays(),
                        row.getGrowthPerDay(),
                        row.getGrowthPercent(),
                        rankingMode
                ))
                .toList();
    }
}
