package com.manhwa.tracker.webtoons.batch;

import com.manhwa.tracker.webtoons.model.Manhwa;
import com.manhwa.tracker.webtoons.model.ManhwaDTO;
import com.manhwa.tracker.webtoons.model.ManhwaTitle;
import com.manhwa.tracker.webtoons.model.MetricSnapshot;
import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.repository.ManhwaRepository;
import com.manhwa.tracker.webtoons.repository.ManhwaTitleRepository;
import com.manhwa.tracker.webtoons.service.TitleNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.ItemProcessor;
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
    private final List<String> skippedTitles = new ArrayList<>();

    @Override
    public MetricSnapshot process(ManhwaDTO dto) throws Exception {
        Manhwa manhwa = resolveManhwa(dto.getTitle());
        if (manhwa == null) {
            if (dto.getTitle() != null) {
                skippedTitles.add(dto.getTitle());
            }
            System.out.println("WARN: Webtoons title not linked to existing manhwa, skipping: " + dto.getTitle());
            return null;
        }

        // 2. Update metadata if it has changed or was previously null
        boolean needsUpdate = false;

        if (manhwa.getCoverImageUrl() == null || !manhwa.getCoverImageUrl().equals(dto.getCoverImageUrl())) {
            manhwa.setCoverImageUrl(dto.getCoverImageUrl());
            needsUpdate = true;
        }

        if (manhwa.getGenre() == null || !manhwa.getGenre().equals(dto.getGenre())) {
            manhwa.setGenre(dto.getGenre());
            needsUpdate = true;
        }

        // Save the Manhwa entity if we added/changed the cover image or genre
        if (needsUpdate) {
            manhwa = manhwaRepository.save(manhwa);
        }

        // 3. Create the Snapshot entity linked to the Manhwa
        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setManhwaId(manhwa.getId());
        snapshot.setMetricType(MetricType.VIEWS);
        snapshot.setMetricValue(dto.getViews());
        snapshot.setSourceId(1);
        snapshot.setCapturedAt(LocalDateTime.now());

        return snapshot;
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
