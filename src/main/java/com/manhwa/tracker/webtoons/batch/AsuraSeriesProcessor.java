package com.manhwa.tracker.webtoons.batch;

import com.manhwa.tracker.webtoons.model.AsuraSeriesDTO;
import com.manhwa.tracker.webtoons.model.Manhwa;
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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AsuraSeriesProcessor implements ItemProcessor<AsuraSeriesDTO, MetricSnapshot> {
    private static final Pattern FOLLOWERS_PATTERN =
            Pattern.compile("Followed\\s+by\\s*([0-9,]+)\\s*people", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOOKMARKS_PATTERN =
            Pattern.compile("\\\\\"bookmarks_count\\\\\":([0-9]+)");
    private static final Pattern RATING_COUNT_PATTERN =
            Pattern.compile("\"ratingCount\"\\s*:\\s*\"?([0-9,]+)\"?", Pattern.CASE_INSENSITIVE);

    private final ManhwaRepository manhwaRepository;
    private final ManhwaTitleRepository manhwaTitleRepository;
    private final ManhwaExternalIdRepository manhwaExternalIdRepository;
    private final CoverSelectionService coverSelectionService;
    private final MangaUpdatesEnrichmentService mangaUpdatesEnrichmentService;
    private final List<String> skippedTitles = new ArrayList<>();

    @Value("${app.asura.user-agent:Mozilla/5.0}")
    private String userAgent;

    @Value("${app.asura.request-delay-ms:200}")
    private long requestDelayMs;

    @Override
    public MetricSnapshot process(AsuraSeriesDTO dto) throws Exception {
        if (requestDelayMs > 0) {
            Thread.sleep(requestDelayMs);
        }
        Document doc = Jsoup.connect(dto.getSeriesUrl())
                .userAgent(userAgent)
                .get();

        String title = dto.getTitle();
        String pageTitle = extractTitle(doc);
        if (pageTitle != null && !pageTitle.isEmpty()) {
            title = pageTitle;
        }
        if (title == null || title.isEmpty()) {
            title = dto.getSeriesUrl();
        }
        String resolvedTitle = title;

        Long followers = extractFollowers(doc);

        Long manhwaId = resolveManhwaId(resolvedTitle);
        if (manhwaId == null) {
            manhwaId = mangaUpdatesEnrichmentService.resolveOrCreateManhwaByTitle(resolvedTitle);
            if (manhwaId == null) {
                if (resolvedTitle != null) {
                    skippedTitles.add(resolvedTitle);
                }
                System.out.println("WARN: Asura title could not be resolved or created, skipping: " + resolvedTitle);
                return null;
            }
            System.out.println("INFO: Asura title created/resolved via MangaUpdates: " + resolvedTitle + " -> manhwaId=" + manhwaId);
        }

        upsertExternalId(manhwaId, dto.getSeriesUrl());
        upsertAsuraAlias(manhwaId, resolvedTitle);
        updateManhwaMetadata(manhwaId, doc);
        mangaUpdatesEnrichmentService.enrichManhwa(manhwaId, resolvedTitle);

        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setManhwaId(manhwaId);
        snapshot.setSourceId(2); // 2 = Asura
        snapshot.setMetricType(MetricType.FOLLOWERS);
        snapshot.setMetricValue(followers);
        snapshot.setCapturedAt(LocalDateTime.now());

        return snapshot;
    }

    private void upsertExternalId(Long manhwaId, String seriesUrl) {
        if (seriesUrl == null || seriesUrl.isBlank()) {
            return;
        }
        String externalId = seriesUrl.trim();
        ManhwaExternalId existingForManhwa = manhwaExternalIdRepository
                .findByManhwaIdAndSource(manhwaId, TitleSource.ASURA)
                .orElse(null);
        if (existingForManhwa != null) {
            boolean changed = false;
            if (!externalId.equals(existingForManhwa.getExternalId())) {
                existingForManhwa.setExternalId(externalId);
                changed = true;
            }
            if (existingForManhwa.getUrl() == null || existingForManhwa.getUrl().isBlank() || !seriesUrl.equals(existingForManhwa.getUrl())) {
                existingForManhwa.setUrl(seriesUrl);
                changed = true;
            }
            if (changed) {
                try {
                    manhwaExternalIdRepository.save(existingForManhwa);
                } catch (DataIntegrityViolationException ex) {
                    System.out.println("WARN: Could not update Asura external ID for manhwaId=" + manhwaId
                            + " externalId=" + externalId + " : " + ex.getMessage());
                }
            }
            return;
        }
        ManhwaExternalId existingByExternalId = manhwaExternalIdRepository
                .findBySourceAndExternalId(TitleSource.ASURA, externalId)
                .orElse(null);
        if (existingByExternalId != null) {
            if (existingByExternalId.getManhwaId() != null && !existingByExternalId.getManhwaId().equals(manhwaId)) {
                System.out.println("WARN: Asura external ID already linked to different manhwaId="
                        + existingByExternalId.getManhwaId() + " for url=" + seriesUrl);
            }
            return;
        }
        try {
            manhwaExternalIdRepository.save(new ManhwaExternalId(manhwaId, TitleSource.ASURA, externalId, seriesUrl));
        } catch (DataIntegrityViolationException ex) {
            System.out.println("WARN: Could not insert Asura external ID for manhwaId=" + manhwaId
                    + " externalId=" + externalId + " : " + ex.getMessage());
        }
    }

    private Long resolveManhwaId(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String normalized = TitleNormalizer.normalize(title);
        if (!normalized.isEmpty()) {
            List<ManhwaTitle> matches = manhwaTitleRepository.findByNormalizedTitle(normalized);
            if (!matches.isEmpty()) {
                return matches.get(0).getManhwaId();
            }
        }
        Manhwa manhwa = manhwaRepository.findByCanonicalTitle(title).orElse(null);
        return manhwa == null ? null : manhwa.getId();
    }

    private void upsertAsuraAlias(Long manhwaId, String title) {
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
                TitleSource.ASURA
        );
        if (exists) {
            return;
        }
        ManhwaTitle alias = new ManhwaTitle(manhwaId, title, normalized, TitleSource.ASURA);
        alias.setCanonical(false);
        manhwaTitleRepository.save(alias);
    }

    @PreDestroy
    public void logSkippedTitles() {
        if (skippedTitles.isEmpty()) {
            return;
        }
        System.out.println("Asura scrape skipped " + skippedTitles.size() + " titles (no alias match).");
        int limit = Math.min(10, skippedTitles.size());
        for (int i = 0; i < limit; i++) {
            System.out.println(" - " + skippedTitles.get(i));
        }
        if (skippedTitles.size() > limit) {
            System.out.println(" - ...");
        }
    }

    private Long extractFollowers(Document doc) {
        for (Element p : doc.select("p")) {
            String text = p.text();
            Matcher matcher = FOLLOWERS_PATTERN.matcher(text);
            if (matcher.find()) {
                String raw = matcher.group(1).replace(",", "");
                try {
                    return Long.parseLong(raw);
                } catch (NumberFormatException ignored) {
                    return 0L;
                }
            }
        }
        Matcher bookmarks = BOOKMARKS_PATTERN.matcher(doc.html());
        if (bookmarks.find()) {
            try {
                return Long.parseLong(bookmarks.group(1));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        Matcher ratingCount = RATING_COUNT_PATTERN.matcher(doc.html());
        if (ratingCount.find()) {
            String raw = ratingCount.group(1).replace(",", "");
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private String extractTitle(Document doc) {
        String ogTitle = doc.select("meta[property=og:title]").attr("content").trim();
        if (!ogTitle.isEmpty()) {
            return ogTitle.replace(" - Asura Scans", "").replace(" | Asura Scans", "").trim();
        }
        String h1 = doc.select("h1").text().trim();
        if (!h1.isEmpty()) {
            return h1;
        }
        String titleTag = doc.select("title").text().trim();
        if (!titleTag.isEmpty()) {
            return titleTag.replace(" - Asura Scans", "").replace(" | Asura Scans", "").trim();
        }
        return "";
    }

    private void updateManhwaMetadata(Long manhwaId, Document doc) {
        Manhwa manhwa = manhwaRepository.findById(manhwaId).orElse(null);
        if (manhwa == null) {
            return;
        }
        String coverImageUrl = extractCoverImageUrl(doc);
        String description = extractDescription(doc);
        String genre = extractGenres(doc);

        coverSelectionService.upsertCoverCandidate(manhwaId, TitleSource.ASURA, coverImageUrl);

        boolean updated = false;
        if (description != null && !description.isBlank() &&
                !description.equals(manhwa.getDescription())) {
            manhwa.setDescription(description);
            updated = true;
        }
        if (genre != null && !genre.isBlank() &&
                !genre.equals(manhwa.getGenre())) {
            manhwa.setGenre(genre);
            updated = true;
        }
        if (updated) {
            manhwaRepository.save(manhwa);
        }
    }

    private String extractCoverImageUrl(Document doc) {
        String ogImage = doc.select("meta[property=og:image]").attr("content").trim();
        if (!ogImage.isEmpty()) {
            return ogImage;
        }
        String altOgImage = doc.select("meta[name=og:image]").attr("content").trim();
        if (!altOgImage.isEmpty()) {
            return altOgImage;
        }
        String cover = doc.select(".series-cover img, .thumb img, .cover img, img.cover, img[alt*=Cover]")
                .attr("src").trim();
        if (!cover.isEmpty()) {
            return cover;
        }
        return "";
    }

    private String extractDescription(Document doc) {
        String ogDescription = sanitizeDescription(doc.select("meta[property=og:description]").attr("content"));
        if (!ogDescription.isEmpty()) {
            return ogDescription;
        }
        String metaDescription = sanitizeDescription(doc.select("meta[name=description]").attr("content"));
        if (!metaDescription.isEmpty()) {
            return metaDescription;
        }
        String summary = sanitizeDescription(doc.select(".series-summary, .description, .series-desc, .summary").text());
        if (!summary.isEmpty()) {
            return summary;
        }
        return "";
    }

    private String extractGenres(Document doc) {
        Set<String> genres = new LinkedHashSet<>();
        for (Element el : doc.select("a[href*=/browse?genres=]")) {
            String text = el.text().trim();
            if (!text.isEmpty()) {
                genres.add(text);
            }
        }
        for (Element el : doc.select(".genres a, .series-genres a, .tags a, a.genre, .info .genre a")) {
            String text = el.text().trim();
            if (!text.isEmpty()) {
                genres.add(text);
            }
        }
        if (genres.isEmpty()) {
            String single = doc.select(".genre, .categories").text().trim();
            if (!single.isEmpty()) {
                genres.add(single);
            }
        }
        if (genres.isEmpty()) {
            return "";
        }
        return String.join(", ", genres);
    }

    private String sanitizeDescription(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String cleaned = raw
                .replaceAll("(?i)\\[object\\s*object\\]", " ")
                .replace("\r", " ")
                .replaceAll("\\s+\\n", "\n")
                .replaceAll("\\n\\s+", "\n")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(,\\s*){2,}", ", ")
                .replaceAll("^[\\s\\uFEFF\\u200B]*[,;:]+\\s*", "")
                .trim();
        if (cleaned.isBlank()) {
            return "";
        }
        long letterOrDigitCount = cleaned.chars().filter(Character::isLetterOrDigit).count();
        if (letterOrDigitCount < 20) {
            return "";
        }
        return cleaned;
    }
}
