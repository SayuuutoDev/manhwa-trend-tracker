package com.manhwa.tracker.webtoons.model;

import java.time.LocalDateTime;

public record BatchJobView(
        String jobName,
        String label,
        boolean running,
        Long executionId,
        String status,
        String exitCode,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        LocalDateTime lastUpdatedAt,
        long readCount,
        long writeCount,
        long filterCount,
        long skipCount,
        long commitCount,
        Integer progressPercent
) {
}
