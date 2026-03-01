package com.manhwa.tracker.webtoons.importer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.manhwa.tracker.webtoons.model.Manhwa;
import com.manhwa.tracker.webtoons.model.ManhwaExternalId;
import com.manhwa.tracker.webtoons.model.ManhwaTitle;
import com.manhwa.tracker.webtoons.model.TitleSource;
import com.manhwa.tracker.webtoons.repository.ManhwaExternalIdRepository;
import com.manhwa.tracker.webtoons.repository.ManhwaRepository;
import com.manhwa.tracker.webtoons.repository.ManhwaTitleRepository;
import com.manhwa.tracker.webtoons.service.TitleNormalizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "app.series.import.enabled", havingValue = "true")
public class SeriesJsonImporter implements CommandLineRunner {
    private final ManhwaRepository manhwaRepository;
    private final ManhwaTitleRepository manhwaTitleRepository;
    private final ManhwaExternalIdRepository manhwaExternalIdRepository;
    private final TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${app.series.import.path:series.json}")
    private String importPath;

    @Value("${app.series.import.batch-size:500}")
    private int batchSize;

    @Value("${app.series.import.progress-interval:10000}")
    private int progressInterval;

    public SeriesJsonImporter(ManhwaRepository manhwaRepository,
                              ManhwaTitleRepository manhwaTitleRepository,
                              ManhwaExternalIdRepository manhwaExternalIdRepository,
                              PlatformTransactionManager transactionManager) {
        this.manhwaRepository = manhwaRepository;
        this.manhwaTitleRepository = manhwaTitleRepository;
        this.manhwaExternalIdRepository = manhwaExternalIdRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(String... args) throws Exception {
        Path path = Path.of(importPath);
        if (!Files.exists(path)) {
            System.out.println("Series import skipped. File not found: " + path.toAbsolutePath());
            return;
        }
        System.out.println("Starting series import from: " + path.toAbsolutePath());

        JsonFactory factory = new JsonFactory();
        long processed = 0;
        long createdManhwas = 0;
        long matchedManhwas = 0;
        long titlesAdded = 0;
        long externalIdsAdded = 0;
        long titleConflicts = 0;
        long externalIdConflicts = 0;

        List<ManhwaTitle> titleBuffer = new ArrayList<>(batchSize);
        List<ManhwaExternalId> externalIdBuffer = new ArrayList<>(batchSize);
        Map<ExternalIdKey, Long> externalIdCache = new HashMap<>();

        try (InputStream input = Files.newInputStream(path);
             JsonParser parser = factory.createParser(input)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalStateException("Expected JSON array at root.");
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                ParsedSeries series = parseSeries(parser);
                processed++;

                Long manhwaId = resolveManhwa(series, externalIdCache);
                if (series.matchedExisting) {
                    matchedManhwas++;
                } else {
                    createdManhwas++;
                }

                if (manhwaId == null) {
                    continue;
                }

                Set<String> titles = new LinkedHashSet<>();
                if (series.enPrimaryTitle != null && !series.enPrimaryTitle.isBlank()) {
                    titles.add(series.enPrimaryTitle);
                }
                titles.addAll(series.allTitles);
                titles.addAll(series.romajiTitles);
                titles.addAll(series.synonyms);

                String canonical = series.enPrimaryTitle;
                if (canonical == null || canonical.isBlank()) {
                    canonical = titles.stream().findFirst().orElse(null);
                }
                if (canonical == null || canonical.isBlank()) {
                    continue;
                }

                Set<String> normalizedTitles = new HashSet<>();
                for (String title : titles) {
                    String normalized = TitleNormalizer.normalize(title);
                    if (normalized.isEmpty() || !normalizedTitles.add(normalized)) {
                        continue;
                    }
                    ManhwaTitle manhwaTitle = new ManhwaTitle(manhwaId, title, normalized, TitleSource.OTHER);
                    manhwaTitle.setCanonical(title.equalsIgnoreCase(canonical));
                    titleBuffer.add(manhwaTitle);
                    titlesAdded++;
                }

                externalIdsAdded += addExternalIds(manhwaId, series, externalIdBuffer, externalIdCache);

                if (titleBuffer.size() >= batchSize) {
                    titleConflicts += persistTitles(titleBuffer);
                    titleBuffer.clear();
                }
                if (externalIdBuffer.size() >= batchSize) {
                    externalIdConflicts += persistExternalIds(externalIdBuffer);
                    externalIdBuffer.clear();
                }

                if (progressInterval > 0 && processed % progressInterval == 0) {
                    System.out.println("Processed " + processed + " series.");
                }
            }
        }

        if (!titleBuffer.isEmpty()) {
            titleConflicts += persistTitles(titleBuffer);
        }
        if (!externalIdBuffer.isEmpty()) {
            externalIdConflicts += persistExternalIds(externalIdBuffer);
        }

        System.out.println("Series import complete.");
        System.out.println("Processed: " + processed);
        System.out.println("Created manhwas: " + createdManhwas);
        System.out.println("Matched manhwas: " + matchedManhwas);
        System.out.println("Titles added: " + titlesAdded);
        System.out.println("External IDs added: " + externalIdsAdded);
        System.out.println("Title conflicts skipped: " + titleConflicts);
        System.out.println("External ID conflicts skipped: " + externalIdConflicts);
    }

    private Long resolveManhwa(ParsedSeries series, Map<ExternalIdKey, Long> externalIdCache) {
        ManhwaExternalId existing = findExistingExternalId(series, externalIdCache);
        if (existing != null) {
            series.matchedExisting = true;
            return existing.getManhwaId();
        }

        String canonical = series.enPrimaryTitle;
        if (canonical == null || canonical.isBlank()) {
            canonical = series.allTitles.stream().findFirst().orElse(null);
        }
        if (canonical == null || canonical.isBlank()) {
            return null;
        }

        Manhwa existingByTitle = manhwaRepository.findByCanonicalTitle(canonical).orElse(null);
        if (existingByTitle != null) {
            series.matchedExisting = true;
            return existingByTitle.getId();
        }

        Manhwa manhwa = new Manhwa(canonical);
        try {
            manhwa = manhwaRepository.save(manhwa);
            return manhwa.getId();
        } catch (DataIntegrityViolationException ex) {
            Manhwa fallback = manhwaRepository.findByCanonicalTitle(canonical).orElse(null);
            if (fallback != null) {
                series.matchedExisting = true;
                return fallback.getId();
            }
            throw ex;
        }
    }

    private ManhwaExternalId findExistingExternalId(ParsedSeries series, Map<ExternalIdKey, Long> externalIdCache) {
        ManhwaExternalId found;
        if ((found = findExternalId(TitleSource.MANGADEX, series.mangadexId, externalIdCache)) != null) return found;
        if ((found = findExternalId(TitleSource.ANILIST, series.anilistId, externalIdCache)) != null) return found;
        if ((found = findExternalId(TitleSource.MYANIMELIST, series.myAnimeListId, externalIdCache)) != null) return found;
        if ((found = findExternalId(TitleSource.KITSU, series.kitsuId, externalIdCache)) != null) return found;
        if ((found = findExternalId(TitleSource.MANGAUPDATES, series.mangaUpdatesId, externalIdCache)) != null) return found;
        return null;
    }

    private ManhwaExternalId findExternalId(TitleSource source, String externalId, Map<ExternalIdKey, Long> externalIdCache) {
        if (externalId == null || externalId.isBlank()) {
            return null;
        }
        ExternalIdKey key = new ExternalIdKey(source, externalId);
        Long cached = externalIdCache.get(key);
        if (cached != null) {
            ManhwaExternalId hit = new ManhwaExternalId();
            hit.setManhwaId(cached);
            hit.setSource(source);
            hit.setExternalId(externalId);
            return hit;
        }
        return manhwaExternalIdRepository.findBySourceAndExternalId(source, externalId)
                .map(found -> {
                    externalIdCache.put(key, found.getManhwaId());
                    return found;
                })
                .orElse(null);
    }

    private long addExternalIds(Long manhwaId, ParsedSeries series, List<ManhwaExternalId> buffer, Map<ExternalIdKey, Long> externalIdCache) {
        long added = 0;
        added += addExternalId(manhwaId, TitleSource.MANGADEX, series.mangadexId, buffer, externalIdCache);
        added += addExternalId(manhwaId, TitleSource.ANILIST, series.anilistId, buffer, externalIdCache);
        added += addExternalId(manhwaId, TitleSource.MYANIMELIST, series.myAnimeListId, buffer, externalIdCache);
        added += addExternalId(manhwaId, TitleSource.KITSU, series.kitsuId, buffer, externalIdCache);
        added += addExternalId(manhwaId, TitleSource.MANGAUPDATES, series.mangaUpdatesId, buffer, externalIdCache);
        return added;
    }

    private long addExternalId(Long manhwaId, TitleSource source, String externalId, List<ManhwaExternalId> buffer, Map<ExternalIdKey, Long> externalIdCache) {
        if (externalId == null || externalId.isBlank()) {
            return 0;
        }
        ExternalIdKey key = new ExternalIdKey(source, externalId);
        Long cached = externalIdCache.get(key);
        if (cached != null) {
            if (!cached.equals(manhwaId)) {
                System.out.println("Warning: external ID conflict for " + source + ":" + externalId
                        + " (existing manhwaId=" + cached + ", new manhwaId=" + manhwaId + "). Skipping.");
            }
            return 0;
        }
        if (manhwaExternalIdRepository.findBySourceAndExternalId(source, externalId).isPresent()) {
            return 0;
        }
        buffer.add(new ManhwaExternalId(manhwaId, source, externalId, null));
        externalIdCache.put(key, manhwaId);
        return 1;
    }

    private int persistTitles(List<ManhwaTitle> buffer) {
        if (buffer.isEmpty()) {
            return 0;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                manhwaTitleRepository.saveAll(buffer);
                manhwaTitleRepository.flush();
            });
            entityManager.clear();
            return 0;
        } catch (DataIntegrityViolationException ex) {
            entityManager.clear();
            int conflicts = 0;
            for (ManhwaTitle title : buffer) {
                try {
                    transactionTemplate.executeWithoutResult(status ->
                            manhwaTitleRepository.saveAndFlush(title));
                } catch (DataIntegrityViolationException ignored) {
                    conflicts++;
                }
            }
            System.out.println("Batch title insert had conflicts; skipped " + conflicts + ".");
            return conflicts;
        }
    }

    private int persistExternalIds(List<ManhwaExternalId> buffer) {
        if (buffer.isEmpty()) {
            return 0;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                manhwaExternalIdRepository.saveAll(buffer);
                manhwaExternalIdRepository.flush();
            });
            entityManager.clear();
            return 0;
        } catch (DataIntegrityViolationException ex) {
            entityManager.clear();
            int conflicts = 0;
            for (ManhwaExternalId externalId : buffer) {
                try {
                    transactionTemplate.executeWithoutResult(status ->
                            manhwaExternalIdRepository.saveAndFlush(externalId));
                } catch (DataIntegrityViolationException ignored) {
                    conflicts++;
                }
            }
            System.out.println("Batch external ID insert had conflicts; skipped " + conflicts + ".");
            return conflicts;
        }
    }

    private ParsedSeries parseSeries(JsonParser parser) throws IOException {
        ParsedSeries series = new ParsedSeries();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.getCurrentName();
            if (field == null) {
                parser.nextToken();
                continue;
            }
            parser.nextToken();
            switch (field) {
                case "all_titles" -> series.allTitles = readStringArray(parser);
                case "en_primary_title" -> series.enPrimaryTitle = readString(parser);
                case "romaji_titles" -> series.romajiTitles = readStringArray(parser);
                case "synonyms" -> series.synonyms = readStringArray(parser);
                case "mangadex_id" -> series.mangadexId = readString(parser);
                case "anilist_id" -> series.anilistId = readString(parser);
                case "my_anime_list_id" -> series.myAnimeListId = readString(parser);
                case "kitsu_id" -> series.kitsuId = readString(parser);
                case "manga_updates_id" -> series.mangaUpdatesId = readString(parser);
                default -> parser.skipChildren();
            }
        }
        return series;
    }

    private String readString(JsonParser parser) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }
        if (parser.currentToken().isNumeric()) {
            return parser.getNumberValue().toString();
        }
        return parser.getValueAsString();
    }

    private List<String> readStringArray(JsonParser parser) throws IOException {
        List<String> values = new ArrayList<>();
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return values;
        }
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            String value = readString(parser);
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
            return values;
        }
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            String value = readString(parser);
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static class ParsedSeries {
        String enPrimaryTitle;
        List<String> allTitles = new ArrayList<>();
        List<String> romajiTitles = new ArrayList<>();
        List<String> synonyms = new ArrayList<>();
        String mangadexId;
        String anilistId;
        String myAnimeListId;
        String kitsuId;
        String mangaUpdatesId;
        boolean matchedExisting = false;
    }

    private record ExternalIdKey(TitleSource source, String externalId) { }
}
