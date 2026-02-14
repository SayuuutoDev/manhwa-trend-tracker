package com.manhwa.tracker.webtoons.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class TrendingManhwaDTO {
    private Long manhwaId;
    private String title;
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
    private TrendingRankingMode rankingMode;
}
