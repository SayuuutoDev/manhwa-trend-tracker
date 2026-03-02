package com.manhwa.tracker.webtoons.model;

public enum RankingWindow {
    DAILY(1),
    WEEKLY(7);

    private final int days;

    RankingWindow(int days) {
        this.days = days;
    }

    public int days() {
        return days;
    }
}
