package com.manhwa.tracker.webtoons.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TapasSeriesDTO {
    private String seriesId;
    private String title;
    private String languageCode;
    private Long viewCount;
    private Long subscriberCount;
    private Long likeCount;
}
