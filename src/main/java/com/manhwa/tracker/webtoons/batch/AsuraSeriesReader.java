package com.manhwa.tracker.webtoons.batch;

import com.manhwa.tracker.webtoons.model.AsuraSeriesDTO;
import org.jsoup.Jsoup;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

@Component
public class AsuraSeriesReader implements ItemReader<AsuraSeriesDTO> {
    private final List<AsuraSeriesDTO> data = new ArrayList<>();
    private int index = 0;

    @Value("${app.asura.base-url:https://asuracomic.net}")
    private String baseUrl;

    @Value("${app.asura.series-path:/series?page=}")
    private String seriesPath;

    @Value("${app.asura.max-pages:1}")
    private int maxPages;

    @Value("${app.asura.user-agent:Mozilla/5.0}")
    private String userAgent;

    @Value("${app.asura.debug:false}")
    private boolean debug;

    @Value("${app.asura.page-delay-ms:300}")
    private long pageDelayMs;

    @Value("${app.asura.stale-page-limit:2}")
    private int stalePageLimit;

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        data.clear();
        index = 0;
    }

    @Override
    public AsuraSeriesDTO read() throws Exception {
        if (data.isEmpty()) {
            Map<String, String> series = new LinkedHashMap<>();
            int stalePages = 0;

            for (int page = 1; page <= maxPages; page++) {
                String url = baseUrl + seriesPath + page;
                Document doc = Jsoup.connect(url)
                        .userAgent(userAgent)
                        .get();
                int beforeCount = series.size();

                // Main series cards on asuracomic.net use relative links like "series/<slug>".
                Elements links = doc.select("a[href^=series/]");
                if (links.isEmpty()) {
                    // Fallback for legacy layouts that expose "/series/<slug>" links.
                    links = doc.select("a[href^=/series/]");
                }
                for (Element link : links) {
                    addSeriesLink(series, link.attr("href"), link.text());
                }

                int afterCount = series.size();
                if (afterCount == beforeCount) {
                    stalePages++;
                } else {
                    stalePages = 0;
                }

                if (stalePageLimit > 0 && stalePages >= stalePageLimit) {
                    if (debug) {
                        System.out.println("DEBUG: Asura series pagination stale at page " + page
                                + " (" + stalePages + " consecutive pages with no new series)");
                    }
                    break;
                }

                if (pageDelayMs > 0 && page < maxPages) {
                    Thread.sleep(pageDelayMs);
                }
            }

            for (Map.Entry<String, String> entry : series.entrySet()) {
                data.add(new AsuraSeriesDTO(entry.getValue(), entry.getKey()));
            }

            if (debug) {
                System.out.println("DEBUG: Asura series found: " + data.size());
                for (int i = 0; i < Math.min(5, data.size()); i++) {
                    AsuraSeriesDTO dto = data.get(i);
                    System.out.println("DEBUG: Asura series sample: " + dto.getTitle() + " -> " + dto.getSeriesUrl());
                }
            }
        }

        if (index < data.size()) {
            return data.get(index++);
        }
        return null;
    }

    private void addSeriesLink(Map<String, String> series, String href, String rawTitle) {
        String normalizedHref = normalizeSeriesHref(href);
        if (normalizedHref.isEmpty()) {
            return;
        }
        String title = normalizeTitle(rawTitle);
        String absUrl;
        if (normalizedHref.startsWith("http")) {
            absUrl = normalizedHref;
        } else {
            absUrl = baseUrl + normalizedHref;
        }
        series.putIfAbsent(absUrl, title);
    }

    private String normalizeSeriesHref(String href) {
        if (href == null) {
            return "";
        }
        String trimmed = href.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String path = trimmed;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            int schemeIndex = path.indexOf("://");
            int firstPathSlash = schemeIndex >= 0 ? path.indexOf('/', schemeIndex + 3) : -1;
            if (firstPathSlash < 0) {
                return "";
            }
            path = path.substring(firstPathSlash);
        }
        if (path.startsWith("series/")) {
            path = "/" + path;
        }
        int index = path.indexOf("/series/");
        if (index < 0) {
            return "";
        }
        path = path.substring(index);
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        int hashIndex = path.indexOf('#');
        if (hashIndex >= 0) {
            path = path.substring(0, hashIndex);
        }
        if ("/series/".equals(path) || path.length() <= "/series/".length()) {
            return "";
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private String normalizeTitle(String rawTitle) {
        if (rawTitle == null) {
            return "";
        }
        String title = rawTitle.trim();
        if (title.equalsIgnoreCase("READ ON OURNEW BETA SITE!")
                || title.equalsIgnoreCase("READ ON OUR NEW BETA SITE!")) {
            return "";
        }
        if (title.contains("...")) {
            return "";
        }
        return title;
    }
}
