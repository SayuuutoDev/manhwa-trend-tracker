package com.manhwa.tracker.webtoons.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manhwa.tracker.webtoons.model.Manhwa;
import com.manhwa.tracker.webtoons.model.ManhwaExternalId;
import com.manhwa.tracker.webtoons.model.ManhwaTitle;
import com.manhwa.tracker.webtoons.model.TitleSource;
import com.manhwa.tracker.webtoons.repository.ManhwaExternalIdRepository;
import com.manhwa.tracker.webtoons.repository.ManhwaRepository;
import com.manhwa.tracker.webtoons.repository.ManhwaTitleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MangaUpdatesEnrichmentService {
    private final ManhwaRepository manhwaRepository;
    private final ManhwaExternalIdRepository manhwaExternalIdRepository;
    private final ManhwaTitleRepository manhwaTitleRepository;
    private final CoverSelectionService coverSelectionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Map<String, Optional<MangaUpdatesMetadata>> seriesCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<MangaUpdatesMetadata>> titleCache = new ConcurrentHashMap<>();

    @Value("${app.mangaupdates.enabled:true}")
    private boolean enabled;

    @Value("${app.mangaupdates.base-url:https://api.mangaupdates.com}")
    private String baseUrl;

    @Value("${app.mangaupdates.user-agent:Mozilla/5.0}")
    private String userAgent;

    @Value("${app.mangaupdates.request-delay-ms:120}")
    private long requestDelayMs;

    @Value("${app.mangaupdates.search.max-results:10}")
    private int maxSearchResults;

    private long lastRequestAt = 0L;

    public MangaUpdatesEnrichmentService(ManhwaRepository manhwaRepository,
                                         ManhwaExternalIdRepository manhwaExternalIdRepository,
                                         ManhwaTitleRepository manhwaTitleRepository,
                                         CoverSelectionService coverSelectionService) {
        this.manhwaRepository = manhwaRepository;
        this.manhwaExternalIdRepository = manhwaExternalIdRepository;
        this.manhwaTitleRepository = manhwaTitleRepository;
        this.coverSelectionService = coverSelectionService;
    }

    public void enrichManhwa(Long manhwaId, String titleHint) {
        if (!enabled || manhwaId == null) {
            return;
        }

        Manhwa manhwa = manhwaRepository.findById(manhwaId).orElse(null);
        if (manhwa == null) {
            return;
        }

        Optional<MangaUpdatesMetadata> metadata = resolveMetadata(manhwaId, titleHint, manhwa.getCanonicalTitle());
        if (metadata.isEmpty()) {
            return;
        }

        MangaUpdatesMetadata data = metadata.get();
        upsertMangaUpdatesExternalId(manhwaId, data.seriesId());
        upsertAliases(manhwaId, data);
        updateManhwa(manhwa, data);
    }

    private Optional<MangaUpdatesMetadata> resolveMetadata(Long manhwaId, String titleHint, String canonicalTitle) {
        List<ManhwaExternalId> existingMuIds = manhwaExternalIdRepository
                .findAllByManhwaIdAndSource(manhwaId, TitleSource.MANGAUPDATES);

        if (!existingMuIds.isEmpty()) {
            existingMuIds.sort(Comparator.comparing(
                    id -> id.getId() == null ? Long.MIN_VALUE : id.getId(),
                    Comparator.reverseOrder()
            ));
            for (ManhwaExternalId existingMuId : existingMuIds) {
                Optional<MangaUpdatesMetadata> byId = getSeries(existingMuId.getExternalId());
                if (byId.isPresent()) {
                    return byId;
                }
            }
        }

        String searchTitle = firstNonBlank(titleHint, canonicalTitle);
        if (searchTitle == null) {
            return Optional.empty();
        }

        return searchSeries(searchTitle);
    }

    private Optional<MangaUpdatesMetadata> getSeries(String seriesId) {
        if (seriesId == null || seriesId.isBlank()) {
            return Optional.empty();
        }
        if (!seriesId.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        return seriesCache.computeIfAbsent(seriesId, id -> {
            try {
                JsonNode root = requestJson("/v1/series/" + id, "GET", null);
                return Optional.of(parseMetadata(root));
            } catch (Exception ex) {
                System.out.println("WARN: MangaUpdates series lookup failed for id=" + id + " : " + ex.getMessage());
                return Optional.empty();
            }
        });
    }

    private Optional<MangaUpdatesMetadata> searchSeries(String title) {
        String normalizedTitle = TitleNormalizer.normalize(title);
        if (normalizedTitle.isBlank()) {
            return Optional.empty();
        }

        return titleCache.computeIfAbsent(normalizedTitle, key -> {
            try {
                String body = objectMapper.writeValueAsString(Map.of("search", title));
                JsonNode root = requestJson("/v1/series/search", "POST", body);
                JsonNode results = root.path("results");
                if (!results.isArray() || results.isEmpty()) {
                    return Optional.empty();
                }

                List<ScoredMetadata> candidates = new ArrayList<>();
                int limit = Math.min(maxSearchResults, results.size());
                for (int i = 0; i < limit; i++) {
                    JsonNode record = results.get(i).path("record");
                    if (record.isMissingNode()) {
                        continue;
                    }
                    MangaUpdatesMetadata metadata = parseMetadata(record);
                    int score = titleScore(normalizedTitle, metadata);
                    candidates.add(new ScoredMetadata(metadata, score));
                }

                return candidates.stream()
                        .filter(c -> c.score() >= 350)
                        .max(Comparator.comparingInt(ScoredMetadata::score))
                        .map(ScoredMetadata::metadata);
            } catch (Exception ex) {
                System.out.println("WARN: MangaUpdates search failed for title='" + title + "' : " + ex.getMessage());
                return Optional.empty();
            }
        });
    }

    private int titleScore(String normalizedTarget, MangaUpdatesMetadata metadata) {
        int best = scoreOne(normalizedTarget, metadata.title());
        for (String alias : metadata.associatedTitles()) {
            best = Math.max(best, scoreOne(normalizedTarget, alias));
        }
        return best;
    }

    private int scoreOne(String normalizedTarget, String candidateTitle) {
        if (candidateTitle == null || candidateTitle.isBlank()) {
            return 0;
        }
        String normalizedCandidate = TitleNormalizer.normalize(candidateTitle);
        if (normalizedCandidate.isBlank()) {
            return 0;
        }
        if (normalizedTarget.equals(normalizedCandidate)) {
            return 1000;
        }
        if (normalizedTarget.contains(normalizedCandidate) || normalizedCandidate.contains(normalizedTarget)) {
            return 750;
        }

        Set<String> targetTokens = new HashSet<>(List.of(normalizedTarget.split(" ")));
        Set<String> candidateTokens = new HashSet<>(List.of(normalizedCandidate.split(" ")));
        long overlap = targetTokens.stream().filter(candidateTokens::contains).count();
        double similarity = overlap / (double) Math.max(1, Math.max(targetTokens.size(), candidateTokens.size()));
        return (int) (similarity * 600);
    }

    private void upsertMangaUpdatesExternalId(Long manhwaId, String seriesId) {
        if (seriesId == null || seriesId.isBlank()) {
            return;
        }

        List<ManhwaExternalId> existingForManhwa = manhwaExternalIdRepository
                .findAllByManhwaIdAndSource(manhwaId, TitleSource.MANGAUPDATES);
        for (ManhwaExternalId existing : existingForManhwa) {
            if (seriesId.equals(existing.getExternalId())) {
                return;
            }
        }

        Optional<ManhwaExternalId> existingBySeries = manhwaExternalIdRepository
                .findBySourceAndExternalId(TitleSource.MANGAUPDATES, seriesId);
        if (existingBySeries.isPresent()) {
            return;
        }

        if (!existingForManhwa.isEmpty()) {
            ManhwaExternalId newest = existingForManhwa.stream()
                    .max(Comparator.comparing(id -> id.getId() == null ? Long.MIN_VALUE : id.getId()))
                    .orElse(existingForManhwa.get(0));
            newest.setExternalId(seriesId);
            manhwaExternalIdRepository.save(newest);
            return;
        }

        manhwaExternalIdRepository.save(new ManhwaExternalId(manhwaId, TitleSource.MANGAUPDATES, seriesId, null));
    }

    private void upsertAliases(Long manhwaId, MangaUpdatesMetadata metadata) {
        addAlias(manhwaId, metadata.title());
        for (String alias : metadata.associatedTitles()) {
            addAlias(manhwaId, alias);
        }
    }

    private void addAlias(Long manhwaId, String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        String normalized = TitleNormalizer.normalize(title);
        if (normalized.isBlank()) {
            return;
        }
        boolean exists = manhwaTitleRepository.existsByManhwaIdAndNormalizedTitleAndSourceAndLanguageIsNull(
                manhwaId,
                normalized,
                TitleSource.MANGAUPDATES
        );
        if (exists) {
            return;
        }
        ManhwaTitle alias = new ManhwaTitle(manhwaId, title, normalized, TitleSource.MANGAUPDATES);
        alias.setCanonical(false);
        manhwaTitleRepository.save(alias);
    }

    private void updateManhwa(Manhwa manhwa, MangaUpdatesMetadata data) {
        boolean changed = false;

        String cover = firstNonBlank(data.coverImageUrl(), data.coverThumbUrl());
        coverSelectionService.upsertCoverCandidate(manhwa.getId(), TitleSource.MANGAUPDATES, cover);

        if (data.description() != null && !data.description().isBlank() && !data.description().equals(manhwa.getDescription())) {
            manhwa.setDescription(data.description());
            changed = true;
        }

        if (data.genreCsv() != null && !data.genreCsv().isBlank() && !data.genreCsv().equals(manhwa.getGenre())) {
            manhwa.setGenre(data.genreCsv());
            changed = true;
        }

        if (changed) {
            manhwaRepository.save(manhwa);
        }
    }

    private MangaUpdatesMetadata parseMetadata(JsonNode root) {
        String seriesId = text(root, "series_id");
        String title = text(root, "title");
        String description = text(root, "description");
        String coverOriginal = text(root.path("image").path("url"), "original");
        String coverThumb = text(root.path("image").path("url"), "thumb");
        String genreCsv = parseGenres(root.path("genres"));

        Set<String> associated = new LinkedHashSet<>();
        for (JsonNode node : root.path("associated")) {
            String alias = text(node, "title");
            if (alias != null && !alias.isBlank()) {
                associated.add(alias);
            }
        }

        return new MangaUpdatesMetadata(
                seriesId,
                title,
                description,
                coverOriginal,
                coverThumb,
                genreCsv,
                List.copyOf(associated)
        );
    }

    private String parseGenres(JsonNode genresNode) {
        if (!genresNode.isArray()) {
            return null;
        }
        List<String> genres = new ArrayList<>();
        for (JsonNode node : genresNode) {
            String genre = text(node, "genre");
            if (genre != null && !genre.isBlank()) {
                genres.add(genre);
            }
        }
        if (genres.isEmpty()) {
            return null;
        }
        return String.join(", ", genres);
    }

    private synchronized JsonNode requestJson(String path, String method, String body) throws IOException, InterruptedException {
        int attempts = 0;
        while (true) {
            attempts++;
            applyRateLimit();

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent);

            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return objectMapper.readTree(response.body());
            }

            boolean retryable = status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
            if (!retryable || attempts >= 3) {
                throw new IllegalStateException("MangaUpdates request failed (" + status + ") for " + path);
            }

            Thread.sleep(400L * attempts);
        }
    }

    private void applyRateLimit() {
        if (requestDelayMs <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestAt;
        if (elapsed < requestDelayMs) {
            try {
                Thread.sleep(requestDelayMs - elapsed);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record MangaUpdatesMetadata(
            String seriesId,
            String title,
            String description,
            String coverImageUrl,
            String coverThumbUrl,
            String genreCsv,
            List<String> associatedTitles
    ) {
    }

    private record ScoredMetadata(MangaUpdatesMetadata metadata, int score) {
    }
}
