package com.manhwa.tracker.webtoons.batch;

import com.manhwa.tracker.webtoons.model.AsuraSeriesDTO;
import com.manhwa.tracker.webtoons.model.Manhwa;
import com.manhwa.tracker.webtoons.model.ManhwaTitle;
import com.manhwa.tracker.webtoons.model.MetricSnapshot;
import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.repository.ManhwaRepository;
import com.manhwa.tracker.webtoons.repository.ManhwaTitleRepository;
import com.manhwa.tracker.webtoons.service.TitleNormalizer;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AsuraSeriesProcessor implements ItemProcessor<AsuraSeriesDTO, MetricSnapshot> {
    private static final Pattern FOLLOWERS_PATTERN =
            Pattern.compile("Followed\\s+by\\s*([0-9,]+)\\s*people", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOOKMARKS_PATTERN =
            Pattern.compile("\\\\\"bookmarks_count\\\\\":([0-9]+)");

    private final ManhwaRepository manhwaRepository;
    private final ManhwaTitleRepository manhwaTitleRepository;
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
            if (resolvedTitle != null) {
                skippedTitles.add(resolvedTitle);
            }
            System.out.println("WARN: Asura title not linked to existing manhwa, skipping: " + resolvedTitle);
            return null;
        }

        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setManhwaId(manhwaId);
        snapshot.setSourceId(2); // 2 = Asura
        snapshot.setMetricType(MetricType.FOLLOWERS);
        snapshot.setMetricValue(followers);
        snapshot.setCapturedAt(LocalDateTime.now());

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
                return matches.get(0).getManhwaId();
            }
        }
        Manhwa manhwa = manhwaRepository.findByCanonicalTitle(title).orElse(null);
        return manhwa == null ? null : manhwa.getId();
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
        return 0L;
    }

    private String extractTitle(Document doc) {
        String ogTitle = doc.select("meta[property=og:title]").attr("content").trim();
        if (!ogTitle.isEmpty()) {
            return ogTitle.replace(" - Asura Scans", "").trim();
        }
        String h1 = doc.select("h1").text().trim();
        if (!h1.isEmpty()) {
            return h1;
        }
        String titleTag = doc.select("title").text().trim();
        if (!titleTag.isEmpty()) {
            return titleTag.replace(" - Asura Scans", "").trim();
        }
        return "";
    }
}
