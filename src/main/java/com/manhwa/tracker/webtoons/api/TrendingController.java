package com.manhwa.tracker.webtoons.api;

import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.model.TrendingManhwaDTO;
import com.manhwa.tracker.webtoons.model.TrendingRankingMode;
import com.manhwa.tracker.webtoons.service.TrendingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class TrendingController {
    private final TrendingService trendingService;

    public TrendingController(TrendingService trendingService) {
        this.trendingService = trendingService;
    }

    @GetMapping("/trending")
    public List<TrendingManhwaDTO> trending(
            @RequestParam(name = "metric", defaultValue = "VIEWS") MetricType metric,
            @RequestParam(name = "limit", defaultValue = "10") int limit,
            @RequestParam(name = "sourceId", required = false) Integer sourceId,
            @RequestParam(name = "mode", defaultValue = "RATE") TrendingRankingMode mode
    ) {
        int cappedLimit = Math.min(Math.max(limit, 1), 100);
        return trendingService.getTrending(metric, sourceId, cappedLimit, mode);
    }
}
