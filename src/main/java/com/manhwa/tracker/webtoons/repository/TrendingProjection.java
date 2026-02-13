package com.manhwa.tracker.webtoons.repository;

import java.time.LocalDateTime;

public interface TrendingProjection {
    Long getManhwaId();
    String getTitle();
    String getCoverImageUrl();
    Long getLatestValue();
    LocalDateTime getLatestAt();
    Long getPreviousValue();
    LocalDateTime getPreviousAt();
    Long getGrowth();
}
