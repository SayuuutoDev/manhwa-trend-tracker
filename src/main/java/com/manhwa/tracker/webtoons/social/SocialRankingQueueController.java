package com.manhwa.tracker.webtoons.social;

import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.model.RankingWindow;
import com.manhwa.tracker.webtoons.model.TrendingManhwaDTO;
import com.manhwa.tracker.webtoons.model.TrendingRankingMode;
import com.manhwa.tracker.webtoons.service.TrendingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
public class SocialRankingQueueController {
    private final TrendingService trendingService;

    public SocialRankingQueueController(TrendingService trendingService) {
        this.trendingService = trendingService;
    }

    @GetMapping("/social-ranking/queue")
    public List<SocialQueueItem> queue(
            @RequestParam(name = "sourceId", required = false) Integer sourceId
    ) {
        MetricType metric = sourceId != null && sourceId == 2 ? MetricType.FOLLOWERS : MetricType.VIEWS;
        List<TrendingManhwaDTO> topDaily = trendingService.getTrending(
                metric,
                sourceId,
                1,
                TrendingRankingMode.RATE,
                RankingWindow.DAILY,
                null,
                null
        );
        String topTitle = topDaily.isEmpty() ? "Today’s top mover" : topDaily.get(0).getTitle();
        String sourceParam = sourceId == null ? "" : "&sourceId=" + sourceId;

        return List.of(
                new SocialQueueItem(
                        "daily-velocity",
                        "Daily Velocity: " + topTitle,
                        "/api/social-ranking.mp4",
                        "metric=" + metric.name()
                                + "&mode=RATE&window=DAILY&format=tiktok&theme=neon&pace=fast&intensity=hype"
                                + "&title=" + encode("Fastest Rising Today")
                                + "&subtitle=" + encode(topTitle)
                                + sourceParam
                ),
                new SocialQueueItem(
                        "daily-breakout",
                        "Daily Breakout Board",
                        "/api/social-ranking.png",
                        "metric=" + metric.name()
                                + "&mode=PCT&window=DAILY&format=instagram&theme=clean&pace=standard"
                                + "&title=" + encode("Breakout of the Day")
                                + sourceParam
                ),
                new SocialQueueItem(
                        "weekly-authority",
                        "Weekly Biggest Gainers",
                        "/api/social-ranking.bundle",
                        "metric=" + metric.name()
                                + "&mode=ABS&window=WEEKLY&format=reels&theme=dark&pace=standard&intensity=standard"
                                + "&title=" + encode("Biggest Weekly Gainers")
                                + sourceParam
                )
        );
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
