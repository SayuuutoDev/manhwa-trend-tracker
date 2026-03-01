package com.manhwa.tracker.webtoons.service;

import com.manhwa.tracker.webtoons.model.Manhwa;
import com.manhwa.tracker.webtoons.model.ManhwaCoverCandidate;
import com.manhwa.tracker.webtoons.model.TitleSource;
import com.manhwa.tracker.webtoons.repository.ManhwaCoverCandidateRepository;
import com.manhwa.tracker.webtoons.repository.ManhwaRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CoverSelectionService {
    private static final Pattern DIMENSION_PATTERN = Pattern.compile("(\\d{2,4})[xX](\\d{2,4})");

    private final ManhwaCoverCandidateRepository candidateRepository;
    private final ManhwaRepository manhwaRepository;

    public CoverSelectionService(ManhwaCoverCandidateRepository candidateRepository,
                                 ManhwaRepository manhwaRepository) {
        this.candidateRepository = candidateRepository;
        this.manhwaRepository = manhwaRepository;
    }

    public void upsertCoverCandidate(Long manhwaId, TitleSource source, String imageUrl) {
        if (manhwaId == null || source == null || imageUrl == null || imageUrl.isBlank()) {
            return;
        }
        String normalizedUrl = imageUrl.trim();
        int qualityScore = scoreCover(source, normalizedUrl);
        int[] dimensions = parseDimensions(normalizedUrl);

        ManhwaCoverCandidate candidate = candidateRepository.findByManhwaIdAndSource(manhwaId, source)
                .orElseGet(ManhwaCoverCandidate::new);
        candidate.setManhwaId(manhwaId);
        candidate.setSource(source);
        candidate.setImageUrl(normalizedUrl);
        candidate.setQualityScore(qualityScore);
        candidate.setWidth(dimensions[0] > 0 ? dimensions[0] : null);
        candidate.setHeight(dimensions[1] > 0 ? dimensions[1] : null);
        candidate.setUpdatedAt(LocalDateTime.now());
        candidateRepository.save(candidate);

        selectBestCover(manhwaId);
    }

    private void selectBestCover(Long manhwaId) {
        List<ManhwaCoverCandidate> candidates = candidateRepository.findAllByManhwaId(manhwaId);
        if (candidates.isEmpty()) {
            return;
        }

        Optional<ManhwaCoverCandidate> best = candidates.stream()
                .max(Comparator
                        .comparingInt(ManhwaCoverCandidate::getQualityScore)
                        .thenComparing(ManhwaCoverCandidate::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())));

        if (best.isEmpty()) {
            return;
        }

        manhwaRepository.findById(manhwaId).ifPresent(manhwa -> {
            String bestUrl = best.get().getImageUrl();
            if (bestUrl != null && !bestUrl.equals(manhwa.getCoverImageUrl())) {
                manhwa.setCoverImageUrl(bestUrl);
                manhwaRepository.save(manhwa);
            }
        });
    }

    private int scoreCover(TitleSource source, String url) {
        int score = sourceBaseScore(source);
        String lower = url.toLowerCase();

        if (lower.contains("thumbnail") || lower.contains("thumb")) {
            score -= 250;
        }
        if (lower.contains("/1x/")) {
            score -= 120;
        }
        if (lower.contains("/2x/") || lower.contains("/3x/")) {
            score += 50;
        }
        if (lower.contains("cover")) {
            score += 25;
        }
        if (lower.endsWith(".webp")) {
            score += 30;
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            score += 15;
        } else if (lower.endsWith(".png")) {
            score += 10;
        }

        int[] dims = parseDimensions(lower);
        if (dims[0] > 0 && dims[1] > 0) {
            int areaBonus = Math.min(300, (dims[0] * dims[1]) / 50000);
            score += areaBonus;
            double ratio = dims[0] / (double) dims[1];
            if (ratio > 0.62 && ratio < 0.72) {
                score += 40;
            }
        }

        return score;
    }

    private int sourceBaseScore(TitleSource source) {
        return switch (source) {
            case ASURA -> 1000;
            case WEBTOONS -> 920;
            case TAPAS -> 860;
            case MANGAUPDATES -> 700;
            default -> 600;
        };
    }

    private int[] parseDimensions(String url) {
        Matcher matcher = DIMENSION_PATTERN.matcher(url);
        while (matcher.find()) {
            try {
                int width = Integer.parseInt(matcher.group(1));
                int height = Integer.parseInt(matcher.group(2));
                if (width > 50 && height > 50) {
                    return new int[]{width, height};
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return new int[]{0, 0};
    }
}
