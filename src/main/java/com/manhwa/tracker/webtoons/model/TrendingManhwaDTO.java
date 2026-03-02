package com.manhwa.tracker.webtoons.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class TrendingManhwaDTO {
    private Long manhwaId;
    private String title;
    private String genre;
    private MetricType metricType;
    private String coverImageUrl;
    private String readUrl;
    private Long latestValue;
    private LocalDateTime latestAt;
    private Long previousValue;
    private LocalDateTime previousAt;
    private Long growth;
    private Double baselineDays;
    private Double growthPerDay;
    private Double growthPercent;
    private Double rankingScore;
    private Double confidenceScore;
    private String confidenceLabel;
    private Integer snapshotAgeHours;
    private Double baselineCoverage;
    private TrendingRankingMode rankingMode;
}
