package com.manhwa.tracker.webtoons.social;

import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.model.TrendingRankingMode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SocialRankingVideoRequest {
    private MetricType metric;
    private TrendingRankingMode mode;
    private Integer sourceId;
    private String title;
    private String subtitle;
    private Boolean includeTimestamp;
}
