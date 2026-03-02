package com.manhwa.tracker.webtoons.social;

public record SocialQueueItem(
        String id,
        String title,
        String endpoint,
        String query
) {
}
