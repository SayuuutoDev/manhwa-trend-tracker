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

import java.util.ArrayList;
import java.util.List;

@Component
public class WebtoonsReader implements ItemReader<ManhwaDTO> {
    private List<ManhwaDTO> data = new ArrayList<>();
    private int index = 0;

    @Value("${app.webtoons.base-url:https://www.webtoons.com}")
    private String baseUrl;

    @Value("${app.webtoons.popular-path:/en/popular}")
    private String popularPath;

    @Value("${app.webtoons.user-agent:Mozilla/5.0}")
    private String userAgent;

    @Value("${app.webtoons.request-timeout-ms:20000}")
    private int requestTimeoutMs;

    @Value("${app.webtoons.max-items:30}")
    private int maxItems;

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        data.clear();
        index = 0;
    }

    @Override
    public ManhwaDTO read() throws Exception {
        if (data.isEmpty()) {
            String popularUrl = buildPopularUrl();
            Document doc = Jsoup.connect(popularUrl)
                    .userAgent(userAgent)
                    .timeout(Math.max(1000, requestTimeoutMs))
                    .get();

            Elements rows = doc.select(".webtoon_list li");
            int depth = maxItems <= 0 ? rows.size() : Math.min(maxItems, rows.size());
            System.out.println("DEBUG: Found " + rows.size() + " webtoons at " + popularUrl + ", ingesting " + depth + ".");

            for (int i = 0; i < depth; i++) {
                Element row = rows.get(i);
                String title = row.select(".info_text .title").text();
                String viewsRaw = row.select(".view_count").text().trim();
                String coverImageUrl = row.select(".image_wrap img").attr("src");
                String seriesUrl = row.select("a").attr("abs:href");
                String genre = row.select(".genre").text();
                if (!title.isEmpty()) {
                    Long views = parseViews(viewsRaw);
                    data.add(new ManhwaDTO(title, views, seriesUrl, coverImageUrl, genre));
                    System.out.println("DEBUG: Scraped [" + title + "] with " + views + " views.");
                }
            }

        }

        if (index < data.size()) {
            return data.get(index++);
        }
        return null;
    }

    private String buildPopularUrl() {
        String normalizedBase = baseUrl == null ? "" : baseUrl.trim();
        String normalizedPath = popularPath == null ? "" : popularPath.trim();
        if (normalizedBase.endsWith("/") && normalizedPath.startsWith("/")) {
            return normalizedBase.substring(0, normalizedBase.length() - 1) + normalizedPath;
        }
        if (!normalizedBase.endsWith("/") && !normalizedPath.startsWith("/")) {
            return normalizedBase + "/" + normalizedPath;
        }
        return normalizedBase + normalizedPath;
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
}
