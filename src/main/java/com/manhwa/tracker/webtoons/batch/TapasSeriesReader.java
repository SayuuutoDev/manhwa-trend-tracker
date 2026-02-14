package com.manhwa.tracker.webtoons.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwa.tracker.webtoons.model.TapasSeriesDTO;
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
import java.util.List;

@Component
public class TapasSeriesReader implements ItemReader<TapasSeriesDTO> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final List<TapasSeriesDTO> pageItems = new ArrayList<>();
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

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        pageItems.clear();
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
}
