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

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        data.clear();
        index = 0;
    }

    @Override
    public AsuraSeriesDTO read() throws Exception {
        if (data.isEmpty()) {
            Map<String, String> series = new LinkedHashMap<>();

            for (int page = 1; page <= maxPages; page++) {
                String url = baseUrl + seriesPath + page;
                Document doc = Jsoup.connect(url)
                        .userAgent(userAgent)
                        .get();

                Elements links = doc.select("a[href^=/series/][class*=overflow-hidden][class*=leading]");
                for (Element link : links) {
                    String href = link.attr("href").trim();
                    if (href.isEmpty()) {
                        continue;
                    }
                    String absUrl = href.startsWith("http") ? href : baseUrl + href;

                    String title = link.text().trim();
                    if (title.equalsIgnoreCase("READ ON OURNEW BETA SITE!")
                            || title.equalsIgnoreCase("READ ON OUR NEW BETA SITE!")) {
                        continue;
                    }
                    if (title.contains("...")) {
                        title = "";
                    }

                    series.putIfAbsent(absUrl, title);
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
}
