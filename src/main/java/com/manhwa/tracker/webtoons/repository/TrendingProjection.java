package com.manhwa.tracker.webtoons.repository;

import java.time.LocalDateTime;

public interface TrendingProjection {
    Long getManhwaId();
    String getTitle();
    String getCoverImageUrl();
    String getReadUrl();
    Long getLatestValue();
    LocalDateTime getLatestAt();
    Long getPreviousValue();
    LocalDateTime getPreviousAt();
    Long getGrowth();
    Double getBaselineDays();
    Double getGrowthPerDay();
    Double getGrowthPercent();
    Double getRankingScore();
}
