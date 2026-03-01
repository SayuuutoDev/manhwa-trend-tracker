package com.manhwa.tracker.webtoons.batch;

import com.manhwa.tracker.webtoons.model.Manhwa;
import com.manhwa.tracker.webtoons.model.ManhwaExternalId;
import com.manhwa.tracker.webtoons.model.ManhwaTitle;
import com.manhwa.tracker.webtoons.model.MetricSnapshot;
import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.model.TapasSeriesDTO;
import com.manhwa.tracker.webtoons.model.TitleSource;
import com.manhwa.tracker.webtoons.repository.ManhwaExternalIdRepository;
import com.manhwa.tracker.webtoons.repository.ManhwaRepository;
import com.manhwa.tracker.webtoons.repository.ManhwaTitleRepository;
import com.manhwa.tracker.webtoons.service.CoverSelectionService;
import com.manhwa.tracker.webtoons.service.MangaUpdatesEnrichmentService;
import com.manhwa.tracker.webtoons.service.TitleNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TapasSeriesProcessor implements ItemProcessor<TapasSeriesDTO, List<MetricSnapshot>> {
    private static final int SOURCE_ID_TAPAS = 3;

    private final ManhwaRepository manhwaRepository;
    private final ManhwaTitleRepository manhwaTitleRepository;
    private final ManhwaExternalIdRepository manhwaExternalIdRepository;
    private final CoverSelectionService coverSelectionService;
    private final MangaUpdatesEnrichmentService mangaUpdatesEnrichmentService;

    private final List<String> skippedTitles = new ArrayList<>();

    @Override
    public List<MetricSnapshot> process(TapasSeriesDTO dto) {
        Long manhwaId = resolveManhwaId(dto.getTitle());
        if (manhwaId == null) {
            manhwaId = mangaUpdatesEnrichmentService.resolveOrCreateManhwaByTitle(dto.getTitle());
            if (manhwaId == null) {
                if (dto.getTitle() != null) {
                    skippedTitles.add(dto.getTitle());
                }
                System.out.println("WARN: Tapas title could not be resolved or created, skipping: " + dto.getTitle());
                return null;
            }
            System.out.println("INFO: Tapas title created/resolved via MangaUpdates: "
                    + dto.getTitle() + " -> manhwaId=" + manhwaId);
        }

        upsertExternalId(manhwaId, dto.getSeriesId());
        upsertAlias(manhwaId, dto);
        coverSelectionService.upsertCoverCandidate(manhwaId, TitleSource.TAPAS, dto.getCoverImageUrl());
        mangaUpdatesEnrichmentService.enrichManhwa(manhwaId, dto.getTitle());
        applyTapasGenres(manhwaId, dto.getGenre());

        List<MetricSnapshot> snapshots = new ArrayList<>(3);
        LocalDateTime now = LocalDateTime.now();
        if (dto.getViewCount() != null) {
            snapshots.add(buildSnapshot(manhwaId, MetricType.VIEWS, dto.getViewCount(), now));
        }
        if (dto.getSubscriberCount() != null) {
            snapshots.add(buildSnapshot(manhwaId, MetricType.SUBSCRIBERS, dto.getSubscriberCount(), now));
        }
        if (dto.getLikeCount() != null) {
            snapshots.add(buildSnapshot(manhwaId, MetricType.LIKES, dto.getLikeCount(), now));
        }

        return snapshots.isEmpty() ? null : snapshots;
    }

    private MetricSnapshot buildSnapshot(Long manhwaId, MetricType type, Long value, LocalDateTime now) {
        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setManhwaId(manhwaId);
        snapshot.setSourceId(SOURCE_ID_TAPAS);
        snapshot.setMetricType(type);
        snapshot.setMetricValue(value);
        snapshot.setCapturedAt(now);
        return snapshot;
    }

    private Long resolveManhwaId(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String normalized = TitleNormalizer.normalize(title);
        if (!normalized.isEmpty()) {
            List<ManhwaTitle> matches = manhwaTitleRepository.findByNormalizedTitle(normalized);
            if (!matches.isEmpty()) {
                Optional<Manhwa> byId = manhwaRepository.findById(matches.get(0).getManhwaId());
                if (byId.isPresent()) {
                    return byId.get().getId();
                }
            }
        }
        Manhwa manhwa = manhwaRepository.findByCanonicalTitle(title).orElse(null);
        return manhwa == null ? null : manhwa.getId();
    }

    private void upsertExternalId(Long manhwaId, String seriesId) {
        if (seriesId == null || seriesId.isBlank()) {
            return;
        }
        String seriesUrl = "https://tapas.io/series/" + seriesId;
        Optional<ManhwaExternalId> existingForManhwa = manhwaExternalIdRepository
                .findByManhwaIdAndSource(manhwaId, TitleSource.TAPAS);
        if (existingForManhwa.isPresent()) {
            ManhwaExternalId row = existingForManhwa.get();
            boolean changed = false;
            if (!seriesId.equals(row.getExternalId())) {
                row.setExternalId(seriesId);
                changed = true;
            }
            if (row.getUrl() == null || row.getUrl().isBlank() || !seriesUrl.equals(row.getUrl())) {
                row.setUrl(seriesUrl);
                changed = true;
            }
            if (changed) {
                manhwaExternalIdRepository.save(row);
            }
            return;
        }

        Optional<ManhwaExternalId> existing = manhwaExternalIdRepository.findBySourceAndExternalId(TitleSource.TAPAS, seriesId);
        if (existing.isPresent()) {
            ManhwaExternalId external = existing.get();
            if (external.getUrl() == null || external.getUrl().isBlank()) {
                external.setUrl(seriesUrl);
                manhwaExternalIdRepository.save(external);
            }
            return;
        }
        manhwaExternalIdRepository.save(new ManhwaExternalId(manhwaId, TitleSource.TAPAS, seriesId, seriesUrl));
    }

    private void upsertAlias(Long manhwaId, TapasSeriesDTO dto) {
        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            return;
        }
        String normalized = TitleNormalizer.normalize(dto.getTitle());
        if (normalized.isEmpty()) {
            return;
        }
        String language = dto.getLanguageCode();
        if (language != null && language.isBlank()) {
            language = null;
        }
        boolean exists = language == null
                ? manhwaTitleRepository.existsByManhwaIdAndNormalizedTitleAndSourceAndLanguageIsNull(
                        manhwaId, normalized, TitleSource.TAPAS)
                : manhwaTitleRepository.existsByManhwaIdAndNormalizedTitleAndSourceAndLanguage(
                        manhwaId, normalized, TitleSource.TAPAS, language);
        if (exists) {
            return;
        }
        ManhwaTitle alias = new ManhwaTitle(manhwaId, dto.getTitle(), normalized, TitleSource.TAPAS);
        alias.setLanguage(language);
        alias.setCanonical(false);
        manhwaTitleRepository.save(alias);
    }

    private void applyTapasGenres(Long manhwaId, String tapasGenreCsv) {
        if (tapasGenreCsv == null || tapasGenreCsv.isBlank()) {
            return;
        }
        manhwaRepository.findById(manhwaId).ifPresent(manhwa -> {
            String merged = mergeGenres(manhwa.getGenre(), tapasGenreCsv);
            if (merged != null && !merged.equals(manhwa.getGenre())) {
                manhwa.setGenre(merged);
                manhwaRepository.save(manhwa);
            }
        });
    }

    private String mergeGenres(String existing, String incoming) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String token : splitGenres(existing)) {
            values.putIfAbsent(canonicalKey(token), token);
        }
        for (String token : splitGenres(incoming)) {
            values.putIfAbsent(canonicalKey(token), token);
        }
        if (values.isEmpty()) {
            return null;
        }
        return String.join(", ", values.values());
    }

    private List<String> splitGenres(String genreCsv) {
        if (genreCsv == null || genreCsv.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : genreCsv.split(",")) {
            String token = part == null ? "" : part.trim();
            if (!token.isEmpty()) {
                values.add(token);
            }
        }
        return values;
    }

    private String canonicalKey(String value) {
        return value.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    @PreDestroy
    public void logSkippedTitles() {
        if (skippedTitles.isEmpty()) {
            return;
        }
        System.out.println("Tapas scrape skipped " + skippedTitles.size() + " titles (no alias match).");
        int limit = Math.min(10, skippedTitles.size());
        for (int i = 0; i < limit; i++) {
            System.out.println(" - " + skippedTitles.get(i));
        }
        if (skippedTitles.size() > limit) {
            System.out.println(" - ...");
        }
    }
}
