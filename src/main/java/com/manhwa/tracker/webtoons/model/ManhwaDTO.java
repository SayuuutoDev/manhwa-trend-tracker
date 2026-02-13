package com.manhwa.tracker.webtoons.model;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ManhwaDTO {
    private String title;
    private Long views;
    private String coverImageUrl;
    private String genre;

}