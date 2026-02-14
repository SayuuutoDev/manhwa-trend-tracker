package com.manhwa.tracker.webtoons.batch;

import com.manhwa.tracker.webtoons.model.ManhwaDTO;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.ItemReader;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class WebtoonsReader implements ItemReader<ManhwaDTO> {
    private List<ManhwaDTO> data = new ArrayList<>();
    private int index = 0;

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        data.clear();
        index = 0;
    }

    @Override
    public ManhwaDTO read() throws Exception {
        if (data.isEmpty()) {
            // 1. Connect with a User-Agent to avoid being blocked
            Document doc = Jsoup.connect("https://www.webtoons.com/en/popular")
                    .userAgent("Mozilla/5.0")
                    .get();

            // 1. Target the list items inside the <ul>
            Elements rows = doc.select(".webtoon_list li");

            System.out.println("DEBUG: Found " + rows.size() + " webtoons in the list.");

            for (Element row : rows) {
                // 2. Select the Title inside the <strong class="title">
                String title = row.select(".info_text .title").text();

                // 3. Select the View Count inside the <div class="view_count">
                String viewsRaw = row.select(".view_count").text().trim(); // e.g., "1.5M" or "93,304"

                // 4. We look for the <img> tag and get the 'src' attribute
                String coverImageUrl = row.select(".image_wrap img").attr("src");
                String seriesUrl = row.select("a").attr("abs:href");

                // 5. Genre
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

    // Helper method to handle "1.5M" -> 1500000
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
