package com.manhwa.tracker.webtoons.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwa.tracker.webtoons.model.TapasSeriesDTO;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TapasSeriesReader implements ItemReader<TapasSeriesDTO> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final List<TapasSeriesDTO> pageItems = new ArrayList<>();
    private final Map<String, String> infoGenreCache = new LinkedHashMap<>();
    private int index = 0;
    private int currentPage = 1;
    private boolean lastPage = false;

    @Value("${app.tapas.base-url:https://story-api.tapas.io}")
    private String baseUrl;

    @Value("${app.tapas.endpoint:/cosmos/api/v1/landing/genre}")
    private String endpoint;

    @Value("${app.tapas.category-type:COMIC}")
    private String categoryType;

    @Value("${app.tapas.subtab-id:17}")
    private int subtabId;

    @Value("${app.tapas.page-size:25}")
    private int pageSize;

    @Value("${app.tapas.user-agent:Mozilla/5.0}")
    private String userAgent;

    @Value("${app.tapas.request-delay-ms:200}")
    private long requestDelayMs;

    @Value("${app.tapas.info-genre.enabled:true}")
    private boolean infoGenreEnabled;

    @Value("${app.tapas.info-request-delay-ms:120}")
    private long infoRequestDelayMs;

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        pageItems.clear();
        infoGenreCache.clear();
        index = 0;
        currentPage = 1;
        lastPage = false;
    }

    @Override
    public TapasSeriesDTO read() throws Exception {
        if (index < pageItems.size()) {
            return pageItems.get(index++);
        }

        if (lastPage) {
            return null;
        }

        pageItems.clear();
        index = 0;

        String url = String.format("%s%s?category_type=%s&subtab_id=%d&page=%d&size=%d",
                baseUrl, endpoint, categoryType, subtabId, currentPage, pageSize);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Tapas request failed (" + response.statusCode() + "): " + url);
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode items = root.path("data").path("items");
        for (JsonNode item : items) {
            TapasSeriesDTO dto = new TapasSeriesDTO();
            dto.setSeriesId(item.path("seriesId").asText());
            dto.setSeriesUrl("https://tapas.io/series/" + dto.getSeriesId());
            dto.setTitle(item.path("title").asText());
            dto.setLanguageCode(item.path("languageCode").asText(null));
            String apiGenres = extractGenres(item);
            String infoGenres = fetchInfoGenres(dto.getSeriesUrl());
            dto.setGenre(mergeValues(apiGenres, infoGenres));
            JsonNode assets = item.path("assetProperty");
            String emailImageUrl = assets.path("emailImage").path("path").asText(null);
            String bookCoverImageUrl = assets.path("bookCoverImage").path("path").asText(null);
            String thumbnailImageUrl = assets.path("thumbnailImage").path("path").asText(null);
            dto.setCoverImageUrl(firstNonBlank(emailImageUrl, bookCoverImageUrl, thumbnailImageUrl));
            JsonNode service = item.path("serviceProperty");
            dto.setViewCount(service.path("viewCount").isMissingNode() ? null : service.path("viewCount").asLong());
            dto.setSubscriberCount(service.path("subscriberCount").isMissingNode() ? null : service.path("subscriberCount").asLong());
            dto.setLikeCount(service.path("likeCount").isMissingNode() ? null : service.path("likeCount").asLong());
            pageItems.add(dto);
        }

        JsonNode pagination = root.path("meta").path("pagination");
        lastPage = pagination.path("last").asBoolean(false);
        currentPage++;

        if (requestDelayMs > 0 && !lastPage) {
            Thread.sleep(requestDelayMs);
        }

        if (pageItems.isEmpty()) {
            return null;
        }
        return pageItems.get(index++);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String extractGenres(JsonNode item) {
        Set<String> genres = new LinkedHashSet<>();
        String mainGenre = item.path("mainGenre").path("value").asText(null);
        if (mainGenre != null && !mainGenre.isBlank()) {
            genres.add(mainGenre.trim());
        }
        for (JsonNode genreNode : item.path("genreList")) {
            String value = genreNode.path("value").asText(null);
            if (value != null && !value.isBlank()) {
                genres.add(value.trim());
            }
        }
        collectTags(item, genres, "tagList");
        collectTags(item, genres, "tags");
        collectTags(item, genres, "hashTagList");
        collectTags(item, genres, "hashtagList");
        collectTags(item, genres, "hashTags");
        if (genres.isEmpty()) {
            return null;
        }
        return String.join(", ", genres);
    }

    private String fetchInfoGenres(String seriesUrl) {
        if (!infoGenreEnabled || seriesUrl == null || seriesUrl.isBlank()) {
            return null;
        }
        String cached = infoGenreCache.get(seriesUrl);
        if (cached != null) {
            return cached;
        }

        try {
            String infoUrl = seriesUrl.endsWith("/") ? seriesUrl + "info" : seriesUrl + "/info";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(infoUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                infoGenreCache.put(seriesUrl, null);
                return null;
            }

            Document doc = Jsoup.parse(response.body());
            Set<String> values = new LinkedHashSet<>();
            for (Element header : doc.select("p.detail-row__header")) {
                if (!"Genres".equalsIgnoreCase(header.text().trim())) {
                    continue;
                }
                Element section = header.parent();
                if (section == null) {
                    continue;
                }
                for (Element chip : section.select("a")) {
                    addTag(values, chip.text());
                }
            }
            for (Element tagChip : doc.select("a.tags__item")) {
                addTag(values, tagChip.text());
            }

            String result = values.isEmpty() ? null : String.join(", ", values);
            infoGenreCache.put(seriesUrl, result);
            if (infoRequestDelayMs > 0) {
                Thread.sleep(infoRequestDelayMs);
            }
            return result;
        } catch (Exception ex) {
            System.out.println("WARN: Tapas info genre fetch failed for " + seriesUrl + " : " + ex.getMessage());
            infoGenreCache.put(seriesUrl, null);
            return null;
        }
    }

    private String mergeValues(String... csvValues) {
        Set<String> values = new LinkedHashSet<>();
        for (String csv : csvValues) {
            if (csv == null || csv.isBlank()) {
                continue;
            }
            for (String part : csv.split(",")) {
                addTag(values, part);
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        return String.join(", ", values);
    }

    private void collectTags(JsonNode item, Set<String> genres, String fieldName) {
        JsonNode node = item.path(fieldName);
        if (node.isMissingNode() || node.isNull()) {
            return;
        }

        if (node.isArray()) {
            for (JsonNode tagNode : node) {
                String raw = null;
                if (tagNode.isTextual()) {
                    raw = tagNode.asText();
                } else if (tagNode.isObject()) {
                    raw = firstNonBlank(
                            tagNode.path("value").asText(null),
                            tagNode.path("name").asText(null),
                            tagNode.path("title").asText(null),
                            tagNode.path("label").asText(null)
                    );
                }
                addTag(genres, raw);
            }
            return;
        }

        if (node.isTextual()) {
            String[] parts = node.asText("").split("[,|]");
            for (String part : parts) {
                addTag(genres, part);
            }
        }
    }

    private void addTag(Set<String> genres, String raw) {
        if (raw == null) {
            return;
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("#")) {
            cleaned = cleaned.substring(1).trim();
        }
        if (!cleaned.isEmpty()) {
            genres.add(cleaned);
        }
    }
}
