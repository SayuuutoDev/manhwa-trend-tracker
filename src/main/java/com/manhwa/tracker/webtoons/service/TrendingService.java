package com.manhwa.tracker.webtoons.service;

import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.model.TrendingManhwaDTO;
import com.manhwa.tracker.webtoons.model.TrendingRankingMode;
import com.manhwa.tracker.webtoons.repository.MetricSnapshotRepository;
import com.manhwa.tracker.webtoons.repository.TrendingProjection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class TrendingService {
    private final MetricSnapshotRepository metricSnapshotRepository;
    private final String excludedGenresRegex;

    public TrendingService(
            MetricSnapshotRepository metricSnapshotRepository,
            @Value("${app.ranking.excluded-genres:}") String excludedGenres
    ) {
        this.metricSnapshotRepository = metricSnapshotRepository;
        this.excludedGenresRegex = buildExcludedGenresRegex(excludedGenres);
    }

    public List<TrendingManhwaDTO> getTrending(
            MetricType metricType,
            Integer sourceId,
            int limit,
            TrendingRankingMode rankingMode
    ) {
        List<TrendingProjection> rows = metricSnapshotRepository.findTrending(
                metricType.name(),
                sourceId,
                limit,
                rankingMode.name(),
                excludedGenresRegex
        );
        return rows.stream()
                .map(row -> new TrendingManhwaDTO(
                        row.getManhwaId(),
                        row.getTitle(),
                        metricType,
                        row.getCoverImageUrl(),
                        row.getReadUrl(),
                        row.getLatestValue(),
                        row.getLatestAt(),
                        row.getPreviousValue(),
                        row.getPreviousAt(),
                        row.getGrowth(),
                        row.getBaselineDays(),
                        row.getGrowthPerDay(),
                        row.getGrowthPercent(),
                        rankingMode
                ))
                .toList();
    }

    private String buildExcludedGenresRegex(String excludedGenres) {
        if (excludedGenres == null || excludedGenres.isBlank()) {
            return null;
        }
        List<String> patterns = Arrays.stream(excludedGenres.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(this::toFlexibleGenrePattern)
                .toList();
        if (patterns.isEmpty()) {
            return null;
        }
        return "(" + String.join("|", patterns) + ")";
    }

    private String toFlexibleGenrePattern(String genre) {
        String[] parts = genre.trim().split("[\\s-]+");
        if (parts.length == 0) {
            return sanitizeGenreToken(genre.trim());
        }
        return Arrays.stream(parts)
                .map(this::sanitizeGenreToken)
                .filter(token -> !token.isBlank())
                .reduce((left, right) -> left + "[[:space:]-]*" + right)
                .orElse(sanitizeGenreToken(genre.trim()));
    }

    private String sanitizeGenreToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        // Keep alnum tokens only; this avoids DB regex escape incompatibilities (e.g. \Q...\E).
        return value.toLowerCase().replaceAll("[^a-z0-9]+", "");
    }
}
