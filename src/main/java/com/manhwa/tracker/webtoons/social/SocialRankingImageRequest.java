package com.manhwa.tracker.webtoons.social;

import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.model.RankingWindow;
import com.manhwa.tracker.webtoons.model.TrendingRankingMode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Parameters for generating a social ranking image.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SocialRankingImageRequest {
    private MetricType metric;
    private TrendingRankingMode mode;
    private RankingWindow window;
    private Integer sourceId;
    private String genre;
    private Integer limit;
    private Long minPreviousValue;
    private String title;
    private String subtitle;
    private Boolean includeTimestamp;
    private String theme;
    private String format;
    private String pace;
    private String intensity;
    private String ctaHandle;
    private String ctaText;
    private String campaignTag;
    private String variant;
}
