package com.manhwa.tracker.webtoons.service;

import com.manhwa.tracker.webtoons.model.TitleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

@Service
public class LocalCoverStorageService {
    private static final Logger log = LoggerFactory.getLogger(LocalCoverStorageService.class);
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final Path rootDirectory;
    private final String baseUrl;

    public LocalCoverStorageService(
            @Value("${app.cover-storage.path:cover-cache}") String storagePath,
            @Value("${app.cover-storage.base-url:http://localhost:8080/covers}") String baseUrl
    ) {
        this.rootDirectory = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to prepare cover storage at " + rootDirectory, e);
        }
        this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "";
    }

    public Optional<String> storeCover(Long manhwaId, TitleSource source, String imageUrl) {
        if (manhwaId == null || source == null || imageUrl == null || imageUrl.isBlank()) {
            return Optional.empty();
        }
        Path sourceDir = rootDirectory.resolve(source.name().toLowerCase(Locale.ROOT));
        try {
            Files.createDirectories(sourceDir);
        } catch (IOException e) {
            log.warn("Unable to create cover directory {}", sourceDir, e);
            return Optional.empty();
        }

        String normalizedUrl = imageUrl.trim();
        String extension = detectExtension(normalizedUrl);
        String filename = manhwaId + "-" + hash(normalizedUrl) + extension;
        Path targetFile = sourceDir.resolve(filename);
        if (Files.exists(targetFile)) {
            return Optional.of(buildPublicUrl(source, filename));
        }
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile(sourceDir, "cover-", ".tmp");
            HttpURLConnection connection = openConnection(normalizedUrl);
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                connection.disconnect();
            }
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(buildPublicUrl(source, filename));
        } catch (IOException e) {
            log.warn("Failed to download cover {} for manhwa {}", normalizedUrl, manhwaId, e);
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }

        if (Files.exists(targetFile)) {
            return Optional.of(buildPublicUrl(source, filename));
        }
        return Optional.empty();
    }

    private HttpURLConnection openConnection(String imageUrl) throws IOException {
        URL url = new URL(imageUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setRequestProperty("Accept", "image/*,*/*;q=0.8");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private String detectExtension(String imageUrl) {
        try {
            String path = new URL(imageUrl).getPath();
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String candidate = path.substring(dot).toLowerCase(Locale.ROOT);
                if (candidate.matches("\\.(jpg|jpeg|png|webp|gif)")) {
                    return candidate;
                }
            }
        } catch (MalformedURLException ignored) {
        }
        return ".jpg";
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String buildPublicUrl(TitleSource source, String filename) {
        String trimmedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return trimmedBase + "/" + source.name().toLowerCase(Locale.ROOT) + "/" + filename;
    }
}
