package com.manhwa.tracker.webtoons.batch;

import com.manhwa.tracker.webtoons.model.ManhwaDTO;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class WebtoonsReader implements ItemReader<ManhwaDTO> {
    private List<ManhwaDTO> data = new ArrayList<>();
    private int index = 0;

    @Value("${app.webtoons.base-url:https://www.webtoons.com}")
    private String baseUrl;

    @Value("${app.webtoons.genres-path:/en/genres}")
    private String genresPath;

    @Value("${app.webtoons.genre-sort-order:MANA}")
    private String genreSortOrder;

    @Value("${app.webtoons.user-agent:Mozilla/5.0}")
    private String userAgent;

    @Value("${app.webtoons.request-timeout-ms:20000}")
    private int requestTimeoutMs;

    @Value("${app.webtoons.max-items:0}")
    private int maxItems;

    @Value("${app.webtoons.excluded-genres:}")
    private String excludedGenres;

    @Value("${app.webtoons.genre-request-delay-ms:120}")
    private int genreRequestDelayMs;

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        data.clear();
        index = 0;
    }

    @Override
    public ManhwaDTO read() throws Exception {
        if (data.isEmpty()) {
            String genresUrl = buildGenresUrl();
            Document genresDoc = Jsoup.connect(genresUrl)
                    .userAgent(userAgent)
                    .timeout(Math.max(1000, requestTimeoutMs))
                    .get();

            Set<String> excludedGenreKeys = parseExcludedGenreKeys(excludedGenres);
            List<GenreLink> genreLinks = extractGenreLinks(genresDoc, excludedGenreKeys);
            if (genreLinks.isEmpty()) {
                System.out.println("WARN: No Webtoons genre links found at " + genresUrl);
                return null;
            }

            Map<String, ManhwaDTO> bySeriesKey = new LinkedHashMap<>();
            int scrapedRows = 0;
            int scrapedGenres = 0;
            int excludedCount = 0;

            for (GenreLink genreLink : genreLinks) {
                if (genreLink.excluded()) {
                    excludedCount++;
                    continue;
                }
                String genreUrl = applySortOrder(genreLink.url(), genreSortOrder);
                if (genreRequestDelayMs > 0) {
                    Thread.sleep(genreRequestDelayMs);
                }
                Document genreDoc = Jsoup.connect(genreUrl)
                        .userAgent(userAgent)
                        .timeout(Math.max(1000, requestTimeoutMs))
                        .get();

                Elements cards = genreDoc.select("ul.webtoon_list li a._genre_title_a");
                scrapedGenres++;
                for (Element card : cards) {
                    String title = card.select(".info_text .title").text().trim();
                    if (title.isEmpty()) {
                        continue;
                    }
                    String viewsRaw = card.select(".view_count").text().trim();
                    String coverImageUrl = card.select(".image_wrap img").attr("abs:src");
                    if (coverImageUrl.isBlank()) {
                        coverImageUrl = card.select(".image_wrap img").attr("src");
                    }
                    String seriesUrl = card.attr("abs:href");
                    if (seriesUrl.isBlank()) {
                        seriesUrl = card.attr("href");
                    }
                    String genre = card.attr("data-genre");
                    if (genre == null || genre.isBlank()) {
                        genre = genreLink.genreDisplay();
                    }

                    Long views = parseViews(viewsRaw);
                    String key = buildSeriesKey(card.attr("data-title-no"), seriesUrl, title);
                    ManhwaDTO existing = bySeriesKey.get(key);
                    if (existing == null || (existing.getViews() != null && views > existing.getViews())) {
                        bySeriesKey.put(key, new ManhwaDTO(title, views, seriesUrl, coverImageUrl, genre));
                    }
                    scrapedRows++;
                }
            }

            List<ManhwaDTO> all = new ArrayList<>(bySeriesKey.values());
            all.sort(Comparator.comparingLong((ManhwaDTO dto) -> dto.getViews() == null ? 0L : dto.getViews()).reversed()
                    .thenComparing(ManhwaDTO::getTitle, String.CASE_INSENSITIVE_ORDER));

            int depth = maxItems <= 0 ? all.size() : Math.min(maxItems, all.size());
            data = new ArrayList<>(all.subList(0, depth));

            System.out.println(
                    "DEBUG: Webtoons genres crawl from " + genresUrl
                            + " | genreLinks=" + genreLinks.size()
                            + " | excludedGenres=" + excludedCount
                            + " | scrapedGenres=" + scrapedGenres
                            + " | rawRows=" + scrapedRows
                            + " | deduped=" + all.size()
                            + " | ingesting=" + depth
            );

        }

        if (index < data.size()) {
            return data.get(index++);
        }
        return null;
    }

    private String buildGenresUrl() {
        String normalizedBase = baseUrl == null ? "" : baseUrl.trim();
        String normalizedPath = genresPath == null ? "" : genresPath.trim();
        if (normalizedBase.endsWith("/") && normalizedPath.startsWith("/")) {
            return normalizedBase.substring(0, normalizedBase.length() - 1) + normalizedPath;
        }
        if (!normalizedBase.endsWith("/") && !normalizedPath.startsWith("/")) {
            return normalizedBase + "/" + normalizedPath;
        }
        return normalizedBase + normalizedPath;
    }

    private List<GenreLink> extractGenreLinks(Document genresDoc, Set<String> excludedGenreKeys) {
        Elements anchors = genresDoc.select("a._snb_tab_a[href*='/en/genres/']");
        Map<String, GenreLink> unique = new LinkedHashMap<>();
        for (Element anchor : anchors) {
            String url = anchor.attr("abs:href");
            if (url == null || url.isBlank()) {
                continue;
            }
            String genreDisplay = anchor.text().trim();
            String genreCode = anchor.attr("data-genre");
            String slug = extractGenreSlug(url);
            String effective = !slug.isBlank() ? slug : (!genreCode.isBlank() ? genreCode : genreDisplay);
            String normalizedKey = normalizeGenreKey(effective);
            boolean excluded = excludedGenreKeys.contains(normalizedKey);
            unique.putIfAbsent(url, new GenreLink(url, genreDisplay, normalizedKey, excluded));
        }
        return new ArrayList<>(unique.values());
    }

    private Set<String> parseExcludedGenreKeys(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return List.of(raw.split(",")).stream()
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(this::normalizeGenreKey)
                .collect(Collectors.toSet());
    }

    private String extractGenreSlug(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path == null) {
                return "";
            }
            String prefix = "/en/genres/";
            int idx = path.indexOf(prefix);
            if (idx < 0) {
                return "";
            }
            String tail = path.substring(idx + prefix.length());
            int slash = tail.indexOf('/');
            if (slash >= 0) {
                tail = tail.substring(0, slash);
            }
            return tail;
        } catch (Exception ex) {
            return "";
        }
    }

    private String normalizeGenreKey(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private String applySortOrder(String url, String sortOrder) {
        if (url == null || url.isBlank()) {
            return url;
        }
        if (sortOrder == null || sortOrder.isBlank()) {
            return url;
        }
        String encoded = URLEncoder.encode(sortOrder.trim(), StandardCharsets.UTF_8);
        if (url.contains("sortOrder=")) {
            return url.replaceAll("sortOrder=[^&]*", "sortOrder=" + encoded);
        }
        return url + (url.contains("?") ? "&" : "?") + "sortOrder=" + encoded;
    }

    private String buildSeriesKey(String titleNo, String seriesUrl, String title) {
        if (titleNo != null && !titleNo.isBlank()) {
            return "titleNo:" + titleNo.trim();
        }
        if (seriesUrl != null && !seriesUrl.isBlank()) {
            return "url:" + seriesUrl.trim();
        }
        return "title:" + title.trim().toLowerCase(Locale.ROOT);
    }

    private Long parseViews(String viewsRaw) {
        if (viewsRaw == null || viewsRaw.isEmpty()) return 0L;

        String clean = viewsRaw.toUpperCase().replace(",", "");
        try {
            if (clean.endsWith("M")) {
                return (long) (Double.parseDouble(clean.replace("M", "")) * 1_000_000);
            } else if (clean.endsWith("K")) {
                return (long) (Double.parseDouble(clean.replace("K", "")) * 1_000);
            }
            return Long.parseLong(clean.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0L;
        }
    }

    private record GenreLink(String url, String genreDisplay, String normalizedKey, boolean excluded) {
    }
}
