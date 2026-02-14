package com.manhwa.tracker.webtoons.model;

public record BatchStartResponse(String jobName, Long executionId, String message) {
}
