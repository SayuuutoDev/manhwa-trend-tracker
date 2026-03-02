package com.manhwa.tracker.webtoons.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api")
public class SocialRankingBundleController {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final SocialRankingImageService imageService;
    private final SocialRankingVideoService videoService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SocialRankingBundleController(
            SocialRankingImageService imageService,
            SocialRankingVideoService videoService
    ) {
        this.imageService = imageService;
        this.videoService = videoService;
    }

    @GetMapping(value = "/social-ranking.bundle", produces = "application/zip")
    public ResponseEntity<byte[]> socialRankingBundle(@ModelAttribute SocialRankingVideoRequest request) {
        try {
            byte[] png = imageService.createImage(toImageRequest(request));
            byte[] mp4 = videoService.createVideo(request);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("generatedAt", LocalDateTime.now().toString());
            metadata.put("metric", request.getMetric());
            metadata.put("mode", request.getMode());
            metadata.put("window", request.getWindow());
            metadata.put("sourceId", request.getSourceId());
            metadata.put("format", request.getFormat());
            metadata.put("theme", request.getTheme());
            metadata.put("pace", request.getPace());
            metadata.put("intensity", request.getIntensity());
            metadata.put("variant", request.getVariant());

            byte[] zipped = zipPayload(png, mp4, objectMapper.writeValueAsBytes(metadata));
            String filename = "social-ranking-" + LocalDateTime.now().format(TS) + ".zip";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .cacheControl(CacheControl.noCache())
                    .body(zipped);
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .cacheControl(CacheControl.noCache())
                    .body(new byte[0]);
        }
    }

    private SocialRankingImageRequest toImageRequest(SocialRankingVideoRequest request) {
        SocialRankingImageRequest mapped = new SocialRankingImageRequest();
        mapped.setMetric(request.getMetric());
        mapped.setMode(request.getMode());
        mapped.setWindow(request.getWindow());
        mapped.setSourceId(request.getSourceId());
        mapped.setGenre(request.getGenre());
        mapped.setLimit(request.getLimit());
        mapped.setMinPreviousValue(request.getMinPreviousValue());
        mapped.setTitle(request.getTitle());
        mapped.setSubtitle(request.getSubtitle());
        mapped.setIncludeTimestamp(request.getIncludeTimestamp());
        mapped.setTheme(request.getTheme());
        mapped.setFormat(request.getFormat());
        mapped.setPace(request.getPace());
        mapped.setIntensity(request.getIntensity());
        mapped.setCtaHandle(request.getCtaHandle());
        mapped.setCtaText(request.getCtaText());
        mapped.setCampaignTag(request.getCampaignTag());
        mapped.setVariant(request.getVariant());
        return mapped;
    }

    private byte[] zipPayload(byte[] png, byte[] mp4, byte[] metadata) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("social-ranking.png"));
            zip.write(png);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("social-ranking.mp4"));
            zip.write(mp4);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("metadata.json"));
            zip.write(metadata);
            zip.closeEntry();

            zip.finish();
            return out.toByteArray();
        }
    }
}
