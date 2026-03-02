package com.manhwa.tracker.webtoons.service;

import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.model.RankingWindow;
import com.manhwa.tracker.webtoons.model.TrendingManhwaDTO;
import com.manhwa.tracker.webtoons.model.TrendingRankingMode;
import com.manhwa.tracker.webtoons.repository.MetricSnapshotRepository;
import com.manhwa.tracker.webtoons.repository.TrendingProjection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class TrendingService {
    private static final int MAX_FETCH_LIMIT = 300;

    private final MetricSnapshotRepository metricSnapshotRepository;
    private final String excludedGenresRegex;
    private final long breakoutMinPreviousDefault;
    private final long breakoutMinPreviousWebtoons;
    private final long breakoutMinPreviousAsura;
    private final long breakoutMinPreviousTapas;

    public TrendingService(
            MetricSnapshotRepository metricSnapshotRepository,
            @Value("${app.ranking.excluded-genres:}") String excludedGenres,
            @Value("${app.ranking.breakout.min-previous-value.default:30000}") long breakoutMinPreviousDefault,
            @Value("${app.ranking.breakout.min-previous-value.webtoons:50000}") long breakoutMinPreviousWebtoons,
            @Value("${app.ranking.breakout.min-previous-value.asura:5000}") long breakoutMinPreviousAsura,
            @Value("${app.ranking.breakout.min-previous-value.tapas:20000}") long breakoutMinPreviousTapas
    ) {
        this.metricSnapshotRepository = metricSnapshotRepository;
        this.excludedGenresRegex = buildExcludedGenresRegex(excludedGenres);
        this.breakoutMinPreviousDefault = Math.max(0, breakoutMinPreviousDefault);
        this.breakoutMinPreviousWebtoons = Math.max(0, breakoutMinPreviousWebtoons);
        this.breakoutMinPreviousAsura = Math.max(0, breakoutMinPreviousAsura);
        this.breakoutMinPreviousTapas = Math.max(0, breakoutMinPreviousTapas);
    }

    public List<TrendingManhwaDTO> getTrending(
            MetricType metricType,
            Integer sourceId,
            int limit,
            TrendingRankingMode rankingMode,
            RankingWindow window,
            String genre,
            Long minPreviousValue
    ) {
        RankingWindow effectiveWindow = window == null ? RankingWindow.WEEKLY : window;
        int windowDays = effectiveWindow.days();
        int fetchLimit = computeFetchLimit(limit, genre);
        Long effectiveMinPreviousValue = resolveMinPreviousValue(
                rankingMode,
                sourceId,
                minPreviousValue,
                windowDays
        );

        List<TrendingProjection> rows = switch (rankingMode) {
            case TOTAL -> metricSnapshotRepository.findTrendingTotal(
                    metricType.name(),
                    sourceId,
                    fetchLimit,
                    excludedGenresRegex
            );
            case ENGAGEMENT -> metricSnapshotRepository.findTrendingEngagement(
                    metricType.name(),
                    MetricType.VIEWS.name(),
                    sourceId,
                    fetchLimit,
                    excludedGenresRegex
            );
            case ACCELERATION -> metricSnapshotRepository.findTrendingAcceleration(
                    metricType.name(),
                    sourceId,
                    fetchLimit,
                    excludedGenresRegex
            );
            case SOCIAL -> computeSocialRows(metricType, sourceId, fetchLimit, effectiveMinPreviousValue, windowDays);
            default -> metricSnapshotRepository.findTrendingGrowth(
                    metricType.name(),
                    sourceId,
                    fetchLimit,
                    rankingMode.name(),
                    excludedGenresRegex,
                    effectiveMinPreviousValue,
                    windowDays
            );
        };

        List<TrendingManhwaDTO> mapped = rows.stream()
                .filter(row -> matchesGenre(row, genre))
                .map(row -> toDto(row, metricType, rankingMode, windowDays))
                .limit(limit)
                .toList();

        if (rankingMode == TrendingRankingMode.SOCIAL) {
            // Ensure explicit score ordering for SOCIAL mode after confidence enrichment.
            mapped = mapped.stream()
                    .sorted(Comparator.comparing(
                            TrendingManhwaDTO::getRankingScore,
                            Comparator.nullsLast(Comparator.reverseOrder())
                    ))
                    .limit(limit)
                    .toList();
        }
        return mapped;
    }

    private TrendingManhwaDTO toDto(
            TrendingProjection row,
            MetricType metricType,
            TrendingRankingMode rankingMode,
            int windowDays
    ) {
        Confidence confidence = computeConfidence(row, windowDays);
        return new TrendingManhwaDTO(
                row.getManhwaId(),
                row.getTitle(),
                row.getGenre(),
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
                row.getRankingScore(),
                confidence.score(),
                confidence.label(),
                confidence.snapshotAgeHours(),
                confidence.baselineCoverage(),
                rankingMode
        );
    }

    private List<TrendingProjection> computeSocialRows(
            MetricType metricType,
            Integer sourceId,
            int fetchLimit,
            Long minPreviousValue,
            int windowDays
    ) {
        List<TrendingProjection> baseRows = metricSnapshotRepository.findTrendingGrowth(
                metricType.name(),
                sourceId,
                fetchLimit,
                TrendingRankingMode.RATE.name(),
                excludedGenresRegex,
                minPreviousValue,
                windowDays
        );
        List<ScoredProjection> scored = new ArrayList<>(baseRows.size());
        for (TrendingProjection row : baseRows) {
            double rate = Math.max(0d, safeDouble(row.getGrowthPerDay()));
            double abs = Math.max(0d, row.getGrowth() == null ? 0d : row.getGrowth());
            double pct = Math.max(0d, safeDouble(row.getGrowthPercent()) * 100d);
            double score = (0.45d * Math.log1p(rate))
                    + (0.35d * Math.log1p(abs))
                    + (0.20d * Math.log1p(pct));
            scored.add(new ScoredProjection(row, score));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredProjection::score).reversed())
                .map(ScoredProjection::asProjection)
                .toList();
    }

    private int computeFetchLimit(int limit, String genre) {
        int expanded = Math.max(1, limit);
        if (genre != null && !genre.isBlank()) {
            expanded = Math.min(MAX_FETCH_LIMIT, Math.max(limit * 6, 40));
        }
        return Math.min(MAX_FETCH_LIMIT, expanded);
    }

    private Long resolveMinPreviousValue(
            TrendingRankingMode rankingMode,
            Integer sourceId,
            Long explicitValue,
            int windowDays
    ) {
        if (explicitValue != null) {
            return explicitValue;
        }
        if (rankingMode != TrendingRankingMode.PCT) {
            return null;
        }
        long sourceBase = switch (sourceId == null ? -1 : sourceId) {
            case 1 -> breakoutMinPreviousWebtoons;
            case 2 -> breakoutMinPreviousAsura;
            case 3 -> breakoutMinPreviousTapas;
            default -> breakoutMinPreviousDefault;
        };
        double scale = Math.max(0.35d, Math.min(1.6d, windowDays / 7.0d));
        return Math.max(0L, Math.round(sourceBase * scale));
    }

    private boolean matchesGenre(TrendingProjection row, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String genre = row.getGenre();
        if (genre == null || genre.isBlank()) {
            return false;
        }
        String normalizedGenre = genre.toLowerCase(Locale.ROOT);
        for (String token : filter.split(",")) {
            String normalizedToken = token.trim().toLowerCase(Locale.ROOT);
            if (!normalizedToken.isEmpty() && normalizedGenre.contains(normalizedToken)) {
                return true;
            }
        }
        return false;
    }

    private Confidence computeConfidence(TrendingProjection row, int windowDays) {
        int snapshotAgeHours = 999;
        if (row.getLatestAt() != null) {
            snapshotAgeHours = (int) Math.max(0L, Duration.between(row.getLatestAt(), LocalDateTime.now()).toHours());
        }

        double freshnessScore;
        if (snapshotAgeHours <= 6) {
            freshnessScore = 1d;
        } else if (snapshotAgeHours <= 24) {
            freshnessScore = 0.9d;
        } else if (snapshotAgeHours <= 48) {
            freshnessScore = 0.75d;
        } else if (snapshotAgeHours <= 72) {
            freshnessScore = 0.55d;
        } else {
            freshnessScore = 0.35d;
        }

        double baselineCoverage = 1d;
        if (row.getBaselineDays() != null && row.getBaselineDays() > 0 && windowDays > 0) {
            baselineCoverage = Math.max(0d, Math.min(1.2d, row.getBaselineDays() / windowDays));
        } else if (row.getPreviousValue() == null) {
            baselineCoverage = 0.55d;
        }
        double baselineStrength = Math.min(1d, baselineCoverage);

        double sampleScore = 0.55d;
        if (row.getPreviousValue() != null && row.getPreviousValue() > 0) {
            sampleScore = Math.min(1d, Math.log10(row.getPreviousValue() + 10d) / 5d);
        }

        double confidenceScore = (0.50d * freshnessScore) + (0.35d * baselineStrength) + (0.15d * sampleScore);
        String confidenceLabel;
        if (confidenceScore >= 0.85d) {
            confidenceLabel = "HIGH";
        } else if (confidenceScore >= 0.65d) {
            confidenceLabel = "MEDIUM";
        } else {
            confidenceLabel = "LOW";
        }

        double roundedScore = Math.round(confidenceScore * 1000d) / 1000d;
        double roundedCoverage = Math.round(baselineCoverage * 1000d) / 1000d;
        return new Confidence(roundedScore, confidenceLabel, snapshotAgeHours, roundedCoverage);
    }

    private double safeDouble(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return 0d;
        }
        return value;
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

    private record Confidence(
            double score,
            String label,
            int snapshotAgeHours,
            double baselineCoverage
    ) {
    }

    private record ScoredProjection(TrendingProjection row, double score) {
        private TrendingProjection asProjection() {
            return new TrendingProjection() {
                @Override
                public Long getManhwaId() {
                    return row.getManhwaId();
                }

                @Override
                public String getTitle() {
                    return row.getTitle();
                }

                @Override
                public String getGenre() {
                    return row.getGenre();
                }

                @Override
                public String getCoverImageUrl() {
                    return row.getCoverImageUrl();
                }

                @Override
                public String getReadUrl() {
                    return row.getReadUrl();
                }

                @Override
                public Long getLatestValue() {
                    return row.getLatestValue();
                }

                @Override
                public LocalDateTime getLatestAt() {
                    return row.getLatestAt();
                }

                @Override
                public Long getPreviousValue() {
                    return row.getPreviousValue();
                }

                @Override
                public LocalDateTime getPreviousAt() {
                    return row.getPreviousAt();
                }

                @Override
                public Long getGrowth() {
                    return row.getGrowth();
                }

                @Override
                public Double getBaselineDays() {
                    return row.getBaselineDays();
                }

                @Override
                public Double getGrowthPerDay() {
                    return row.getGrowthPerDay();
                }

                @Override
                public Double getGrowthPercent() {
                    return row.getGrowthPercent();
                }

                @Override
                public Double getRankingScore() {
                    return score;
                }
            };
        }
    }
}
