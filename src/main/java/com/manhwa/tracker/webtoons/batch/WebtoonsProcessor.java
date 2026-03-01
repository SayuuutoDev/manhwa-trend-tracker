package com.manhwa.tracker.webtoons.batch;

import com.manhwa.tracker.webtoons.model.Manhwa;
import com.manhwa.tracker.webtoons.model.ManhwaDTO;
import com.manhwa.tracker.webtoons.model.ManhwaExternalId;
import com.manhwa.tracker.webtoons.model.ManhwaTitle;
import com.manhwa.tracker.webtoons.model.MetricSnapshot;
import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.model.TitleSource;
import com.manhwa.tracker.webtoons.repository.ManhwaExternalIdRepository;
import com.manhwa.tracker.webtoons.repository.ManhwaRepository;
import com.manhwa.tracker.webtoons.repository.ManhwaTitleRepository;
import com.manhwa.tracker.webtoons.service.CoverSelectionService;
import com.manhwa.tracker.webtoons.service.MangaUpdatesEnrichmentService;
import com.manhwa.tracker.webtoons.service.TitleNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor // Automatically injects the repository
public class WebtoonsProcessor implements ItemProcessor<ManhwaDTO, MetricSnapshot> {

    private final ManhwaRepository manhwaRepository;
    private final ManhwaTitleRepository manhwaTitleRepository;
    private final ManhwaExternalIdRepository manhwaExternalIdRepository;
    private final CoverSelectionService coverSelectionService;
    private final MangaUpdatesEnrichmentService mangaUpdatesEnrichmentService;
    private final List<String> skippedTitles = new ArrayList<>();

    @Override
    public MetricSnapshot process(ManhwaDTO dto) throws Exception {
        Manhwa manhwa = resolveManhwa(dto.getTitle());
        if (manhwa == null) {
            Long resolvedId = mangaUpdatesEnrichmentService.resolveOrCreateManhwaByTitle(dto.getTitle());
            if (resolvedId == null) {
                if (dto.getTitle() != null) {
                    skippedTitles.add(dto.getTitle());
                }
                System.out.println("WARN: Webtoons title could not be resolved or created, skipping: " + dto.getTitle());
                return null;
            }
            manhwa = manhwaRepository.findById(resolvedId).orElse(null);
            if (manhwa == null) {
                if (dto.getTitle() != null) {
                    skippedTitles.add(dto.getTitle());
                }
                System.out.println("WARN: Webtoons resolved manhwa missing after create, skipping: " + dto.getTitle());
                return null;
            }
            System.out.println("INFO: Webtoons title created/resolved via MangaUpdates: "
                    + dto.getTitle() + " -> manhwaId=" + manhwa.getId());
        }

        // 2. Update metadata if it has changed or was previously null
        boolean needsUpdate = false;

        upsertExternalId(manhwa.getId(), dto.getSeriesUrl());
        upsertWebtoonsAlias(manhwa.getId(), dto.getTitle());
        coverSelectionService.upsertCoverCandidate(manhwa.getId(), TitleSource.WEBTOONS, dto.getCoverImageUrl());

        if (manhwa.getGenre() == null || !manhwa.getGenre().equals(dto.getGenre())) {
            manhwa.setGenre(dto.getGenre());
            needsUpdate = true;
        }

        // Save the Manhwa entity only when textual metadata changed (covers are handled by CoverSelectionService)
        if (needsUpdate) {
            manhwa = manhwaRepository.save(manhwa);
        }

        mangaUpdatesEnrichmentService.enrichManhwa(manhwa.getId(), dto.getTitle());

        // 3. Create the Snapshot entity linked to the Manhwa
        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setManhwaId(manhwa.getId());
        snapshot.setMetricType(MetricType.VIEWS);
        snapshot.setMetricValue(dto.getViews());
        snapshot.setSourceId(1);
        snapshot.setCapturedAt(LocalDateTime.now());

        return snapshot;
    }

    private void upsertExternalId(Long manhwaId, String seriesUrl) {
        if (seriesUrl == null || seriesUrl.isBlank()) {
            return;
        }
        String externalId = seriesUrl.trim();
        Optional<ManhwaExternalId> existingForManhwa = manhwaExternalIdRepository
                .findByManhwaIdAndSource(manhwaId, TitleSource.WEBTOONS);
        if (existingForManhwa.isPresent()) {
            ManhwaExternalId row = existingForManhwa.get();
            boolean changed = false;
            if (!externalId.equals(row.getExternalId())) {
                row.setExternalId(externalId);
                changed = true;
            }
            if (row.getUrl() == null || row.getUrl().isBlank() || !seriesUrl.equals(row.getUrl())) {
                row.setUrl(seriesUrl);
                changed = true;
            }
            if (changed) {
                try {
                    manhwaExternalIdRepository.save(row);
                } catch (DataIntegrityViolationException ex) {
                    System.out.println("WARN: Could not update Webtoons external ID for manhwaId=" + manhwaId
                            + " externalId=" + externalId + " : " + ex.getMessage());
                }
            }
            return;
        }
        if (manhwaExternalIdRepository.findBySourceAndExternalId(TitleSource.WEBTOONS, externalId).isPresent()) {
            return;
        }
        try {
            manhwaExternalIdRepository.save(new ManhwaExternalId(manhwaId, TitleSource.WEBTOONS, externalId, seriesUrl));
        } catch (DataIntegrityViolationException ex) {
            System.out.println("WARN: Could not insert Webtoons external ID for manhwaId=" + manhwaId
                    + " externalId=" + externalId + " : " + ex.getMessage());
        }
    }

    private void upsertWebtoonsAlias(Long manhwaId, String title) {
        if (manhwaId == null || title == null || title.isBlank()) {
            return;
        }
        String normalized = TitleNormalizer.normalize(title);
        if (normalized.isBlank()) {
            return;
        }
        boolean exists = manhwaTitleRepository.existsByManhwaIdAndNormalizedTitleAndSourceAndLanguageIsNull(
                manhwaId,
                normalized,
                TitleSource.WEBTOONS
        );
        if (exists) {
            return;
        }
        ManhwaTitle alias = new ManhwaTitle(manhwaId, title, normalized, TitleSource.WEBTOONS);
        alias.setCanonical(false);
        manhwaTitleRepository.save(alias);
    }

    private Manhwa resolveManhwa(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String normalized = TitleNormalizer.normalize(title);
        if (!normalized.isEmpty()) {
            List<ManhwaTitle> matches = manhwaTitleRepository.findByNormalizedTitle(normalized);
            if (!matches.isEmpty()) {
                Optional<Manhwa> byId = manhwaRepository.findById(matches.get(0).getManhwaId());
                if (byId.isPresent()) {
                    return byId.get();
                }
            }
        }
        return manhwaRepository.findByCanonicalTitle(title).orElse(null);
    }

    @PreDestroy
    public void logSkippedTitles() {
        if (skippedTitles.isEmpty()) {
            return;
        }
        System.out.println("Webtoons scrape skipped " + skippedTitles.size() + " titles (no alias match).");
        int limit = Math.min(10, skippedTitles.size());
        for (int i = 0; i < limit; i++) {
            System.out.println(" - " + skippedTitles.get(i));
        }
        if (skippedTitles.size() > limit) {
            System.out.println(" - ...");
        }
    }
}
