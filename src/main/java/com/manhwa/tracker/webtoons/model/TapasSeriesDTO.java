package com.manhwa.tracker.webtoons.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TapasSeriesDTO {
    private String seriesId;
    private String seriesUrl;
    private String title;
    private String languageCode;
    private String coverImageUrl;
    private Long viewCount;
    private Long subscriberCount;
    private Long likeCount;
}
