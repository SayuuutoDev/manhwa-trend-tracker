package com.manhwa.tracker.webtoons.social;

import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.model.TitleSource;
import com.manhwa.tracker.webtoons.model.TrendingManhwaDTO;
import com.manhwa.tracker.webtoons.model.TrendingRankingMode;
import com.manhwa.tracker.webtoons.service.LocalCoverStorageService;
import com.manhwa.tracker.webtoons.service.TrendingService;
import org.jcodec.api.awt.AWTSequenceEncoder;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class SocialRankingVideoService {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;
    private static final int FPS = 24;
    private static final int DURATION_SECONDS = 15;
    private static final int LIMIT = 5;
    private static final int TOTAL_FRAMES = FPS * DURATION_SECONDS;
    private static final int INTRO_FRAMES = FPS * 2;
    private static final float RANK_TEASE_WINDOW = 0.16f;
    private static final float TRANSITION_WINDOW = 0.22f;
    private static final DecimalFormat INTEGER_FORMAT = new DecimalFormat("#,###");
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#,###.0");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private final TrendingService trendingService;
    private final LocalCoverStorageService localCoverStorageService;
    private final BufferedImage placeholderCover;

    static {
        ImageIO.scanForPlugins();
    }

    public SocialRankingVideoService(
            TrendingService trendingService,
            LocalCoverStorageService localCoverStorageService
    ) {
        this.trendingService = trendingService;
        this.localCoverStorageService = localCoverStorageService;
        this.placeholderCover = createFallbackCover();
    }

    public byte[] createVideo(SocialRankingVideoRequest request) throws IOException {
        SocialRankingVideoRequest normalized = normalize(request);
        List<TrendingManhwaDTO> rows = trendingService.getTrending(
                normalized.getMetric(),
                normalized.getSourceId(),
                LIMIT,
                normalized.getMode(),
                null
        );
        if (rows.size() > LIMIT) {
            rows = rows.subList(0, LIMIT);
        }

        Map<Long, BufferedImage> covers = preloadCovers(rows, normalized.getSourceId());
        Path tempVideo = Files.createTempFile("social-ranking-", ".mp4");
        try {
            AWTSequenceEncoder encoder = AWTSequenceEncoder.createSequenceEncoder(tempVideo.toFile(), FPS);
            for (int frame = 0; frame < TOTAL_FRAMES; frame++) {
                BufferedImage image = renderFrame(normalized, rows, covers, frame);
                encoder.encodeImage(image);
            }
            encoder.finish();
            return Files.readAllBytes(tempVideo);
        } finally {
            Files.deleteIfExists(tempVideo);
        }
    }

    private SocialRankingVideoRequest normalize(SocialRankingVideoRequest request) {
        SocialRankingVideoRequest normalized = new SocialRankingVideoRequest();
        normalized.setMetric(request.getMetric() == null ? MetricType.VIEWS : request.getMetric());
        normalized.setMode(request.getMode() == null ? TrendingRankingMode.RATE : request.getMode());
        normalized.setSourceId(request.getSourceId());
        normalized.setTitle(request.getTitle());
        normalized.setSubtitle(request.getSubtitle());
        normalized.setIncludeTimestamp(request.getIncludeTimestamp() == null ? Boolean.TRUE : request.getIncludeTimestamp());
        return normalized;
    }

    private BufferedImage renderFrame(
            SocialRankingVideoRequest request,
            List<TrendingManhwaDTO> rows,
            Map<Long, BufferedImage> covers,
            int frame
    ) {
        BufferedImage canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        applyRenderingHints(g);

        if (rows.isEmpty()) {
            drawBackground(g);
            drawEmpty(g);
            g.dispose();
            return canvas;
        }

        if (frame < INTRO_FRAMES) {
            drawIntroFrame(g, request, frame / (float) INTRO_FRAMES);
            drawProgress(g, frame + 1, TOTAL_FRAMES);
            drawFooter(g, request);
            g.dispose();
            return canvas;
        }

        int mainFrames = TOTAL_FRAMES - INTRO_FRAMES;
        int sceneFrames = Math.max(1, mainFrames / rows.size());
        int mainFrame = frame - INTRO_FRAMES;
        int scene = Math.min(rows.size() - 1, mainFrame / sceneFrames);
        int currentRowIndex = rows.size() - 1 - scene; // #5 -> #1
        int sceneFrame = mainFrame - (scene * sceneFrames);
        float sceneProgress = Math.max(0f, Math.min(1f, sceneFrame / (float) sceneFrames));

        int previousScene = Math.max(0, scene - 1);
        int previousRowIndex = rows.size() - 1 - previousScene;
        TrendingManhwaDTO currentRow = rows.get(currentRowIndex);
        TrendingManhwaDTO previousRow = rows.get(previousRowIndex);
        BufferedImage currentCover = covers.getOrDefault(currentRow.getManhwaId(), placeholderCover);
        BufferedImage previousCover = covers.getOrDefault(previousRow.getManhwaId(), placeholderCover);

        if (sceneProgress < RANK_TEASE_WINDOW) {
            drawBackground(g);
            float tease = sceneProgress / RANK_TEASE_WINDOW;
            drawRankTeaseOverlay(g, currentRowIndex + 1, request, tease);
        } else {
            float revealProgress = (sceneProgress - RANK_TEASE_WINDOW) / (1f - RANK_TEASE_WINDOW);
            drawBackground(g);
            if (scene > 0 && revealProgress < TRANSITION_WINDOW) {
                float t = revealProgress / TRANSITION_WINDOW;
                drawCoverLayer(g, previousCover, -130f * t, 1.02f + (0.02f * t), 1f - t);
                drawCoverLayer(g, currentCover, 130f * (1f - t), 1.06f - (0.04f * t), t);
                drawSceneOverlay(g, previousRow, previousRowIndex + 1, request, 1f - t);
                drawSceneOverlay(g, currentRow, currentRowIndex + 1, request, t);
            } else {
                float zoom = 1.04f + (0.03f * (1f - revealProgress));
                drawCoverLayer(g, currentCover, 0f, zoom, 1f);
                float overlayAlpha = Math.min(1f, Math.max(0f, (revealProgress - 0.03f) / 0.18f));
                drawSceneOverlay(g, currentRow, currentRowIndex + 1, request, overlayAlpha);
            }
        }

        drawProgress(g, frame + 1, TOTAL_FRAMES);
        drawFooter(g, request);
        g.dispose();
        return canvas;
    }

    private void drawBackground(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, new Color(19, 24, 48), 0, HEIGHT, new Color(8, 10, 22)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(new Color(66, 201, 255, 20));
        g.fillOval(-150, -220, 700, 700);
        g.setColor(new Color(255, 114, 66, 18));
        g.fillOval(560, -200, 700, 700);
    }

    private void drawIntroFrame(Graphics2D g, SocialRankingVideoRequest request, float progress) {
        drawBackground(g);
        float eased = (float) (1 - Math.pow(1 - progress, 3));
        int rise = Math.round((1f - eased) * 80f);
        float alpha = Math.max(0f, Math.min(1f, eased * 1.2f));

        AlphaComposite old = (AlphaComposite) g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

        g.setColor(new Color(255, 255, 255, 24));
        g.fill(new RoundRectangle2D.Double(60, 250 + rise, WIDTH - 120, 400, 40, 40));

        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 74));
        String title = request.getTitle() == null ? "Top 5 Trending Manhwa" : request.getTitle();
        g.drawString(title, 88, 395 + rise);

        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        g.setColor(new Color(200, 230, 255));
        String subtitle = request.getSubtitle() == null || request.getSubtitle().isBlank()
                ? "15s countdown  •  #5 to #1 reveal"
                : request.getSubtitle();
        g.drawString(subtitle, 92, 456 + rise);

        g.setFont(new Font("SansSerif", Font.PLAIN, 30));
        g.setColor(new Color(238, 246, 255, 220));
        g.drawString("Metric: " + request.getMetric().name() + "  •  Mode: " + request.getMode().name() + "  •  " + resolveSource(request.getSourceId()), 92, 516 + rise);

        g.setFont(new Font("SansSerif", Font.BOLD, 120));
        g.setColor(new Color(255, 255, 255, 210));
        g.drawString("READY?", 90, 760 + Math.round((1f - eased) * 34f));

        g.setComposite(old);
    }

    private void drawRankTeaseOverlay(Graphics2D g, int rank, SocialRankingVideoRequest request, float progress) {
        float eased = (float) (1 - Math.pow(1 - progress, 3));
        AlphaComposite old = (AlphaComposite) g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.88f));
        g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 145), 0, HEIGHT, new Color(0, 0, 0, 210)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setComposite(old);

        int pulseRise = Math.round((1f - eased) * 45f);
        g.setColor(new Color(255, 255, 255, 210));
        g.setFont(new Font("SansSerif", Font.BOLD, 50));
        g.drawString("NEXT RANK", 78, 260 + pulseRise);

        g.setColor(new Color(140, 241, 255));
        g.setFont(new Font("SansSerif", Font.BOLD, 260));
        g.drawString("#" + rank, 78, 520 + pulseRise);

        g.setColor(new Color(255, 255, 255, 220));
        g.setFont(new Font("SansSerif", Font.BOLD, 44));
        g.drawString("Who climbs this slot?", 78, 610 + pulseRise);

        g.setColor(new Color(207, 227, 255, 230));
        g.setFont(new Font("SansSerif", Font.PLAIN, 30));
        String subtitle = request.getSubtitle() == null || request.getSubtitle().isBlank()
                ? "Bottom to top reveal in progress..."
                : request.getSubtitle();
        g.drawString(subtitle, 78, 664 + pulseRise);
    }

    private void drawSceneOverlay(Graphics2D g, TrendingManhwaDTO row, int rank, SocialRankingVideoRequest request, float alpha) {
        AlphaComposite old = (AlphaComposite) g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));

        g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 120), 0, HEIGHT, new Color(0, 0, 0, 210)));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        g.setColor(new Color(255, 255, 255, 38));
        g.fill(new RoundRectangle2D.Double(48, 58, WIDTH - 96, 92, 22, 22));
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        String headerTitle = request.getTitle() == null ? "Top 5 Trending Manhwa" : request.getTitle();
        g.drawString(headerTitle, 78, 118);

        g.setColor(new Color(255, 255, 255, 200));
        g.setFont(new Font("SansSerif", Font.BOLD, 50));
        g.drawString("#" + rank, 74, 290);

        int titleX = 74;
        int titleY = 370;
        int maxWidth = WIDTH - 148;
        int maxLines = 9;
        Font titleFont = pickTitleFont(g, row.getTitle(), maxWidth, maxLines);
        g.setFont(titleFont);
        g.setColor(Color.WHITE);
        drawWrappedText(g, safeTitle(row.getTitle()), titleX, titleY, maxWidth, titleFont.getSize() + 12, maxLines);

        g.setColor(new Color(84, 225, 255, 44));
        g.fill(new RoundRectangle2D.Double(62, HEIGHT - 320, WIDTH - 124, 118, 26, 26));
        g.setColor(new Color(155, 244, 255, 180));
        g.draw(new RoundRectangle2D.Double(62, HEIGHT - 320, WIDTH - 124, 118, 26, 26));

        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        g.setColor(new Color(217, 251, 255));
        g.drawString("GROWTH / DAY", 92, HEIGHT - 272);

        String growthDay = row.getGrowthPerDay() == null ? "-" : ("+" + DECIMAL_FORMAT.format(row.getGrowthPerDay()));
        g.setFont(new Font("SansSerif", Font.BOLD, 58));
        g.setColor(Color.WHITE);
        g.drawString(growthDay, 92, HEIGHT - 220);

        g.setFont(new Font("SansSerif", Font.PLAIN, 30));
        g.setColor(new Color(233, 239, 255, 220));
        g.drawString("Total: " + INTEGER_FORMAT.format(row.getLatestValue()) + " " + row.getMetricType().name().toLowerCase(Locale.ROOT), 74, HEIGHT - 158);

        if (request.getIncludeTimestamp()) {
            g.setFont(new Font("SansSerif", Font.PLAIN, 24));
            String ts = "Generated " + LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            FontMetrics metrics = g.getFontMetrics();
            int width = metrics.stringWidth(ts);
            g.setColor(new Color(255, 255, 255, 190));
            g.drawString(ts, WIDTH - width - 74, HEIGHT - 54);
        }

        g.setComposite(old);
    }
    private void drawCoverLayer(Graphics2D g, BufferedImage cover, float offsetX, float zoom, float alpha) {
        AlphaComposite old = (AlphaComposite) g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
        int iw = Math.max(1, cover.getWidth());
        int ih = Math.max(1, cover.getHeight());
        double baseScale = Math.max(WIDTH / (double) iw, HEIGHT / (double) ih);
        double scale = baseScale * zoom;
        int dw = (int) Math.round(iw * scale);
        int dh = (int) Math.round(ih * scale);
        int dx = Math.round((WIDTH - dw) / 2f + offsetX);
        int dy = (HEIGHT - dh) / 2;
        g.drawImage(cover, dx, dy, dw, dh, null);
        g.setComposite(old);
    }

    private void drawProgress(Graphics2D g, int frame, int totalFrames) {
        int barX = 66;
        int barY = HEIGHT - 144;
        int barW = WIDTH - 132;
        int barH = 14;
        float progress = Math.max(0f, Math.min(1f, frame / (float) totalFrames));
        g.setColor(new Color(255, 255, 255, 46));
        g.fillRoundRect(barX, barY, barW, barH, 16, 16);
        g.setColor(new Color(80, 216, 255));
        g.fillRoundRect(barX, barY, Math.round(barW * progress), barH, 16, 16);
    }

    private void drawFooter(Graphics2D g, SocialRankingVideoRequest request) {
        g.setColor(new Color(255, 255, 255, 170));
        g.setFont(new Font("SansSerif", Font.PLAIN, 23));
        g.drawString("Source: " + resolveSource(request.getSourceId()) + "  •  Bottom to top reveal", 68, HEIGHT - 92);
    }

    private void drawEmpty(Graphics2D g) {
        g.setColor(new Color(255, 255, 255, 210));
        g.setFont(new Font("SansSerif", Font.BOLD, 44));
        g.drawString("No ranking data available.", 120, HEIGHT / 2 - 20);
        g.setFont(new Font("SansSerif", Font.PLAIN, 30));
        g.setColor(new Color(220, 231, 255, 220));
        g.drawString("Run scrape jobs, then retry /api/social-ranking.mp4", 120, HEIGHT / 2 + 34);
    }

    private Map<Long, BufferedImage> preloadCovers(List<TrendingManhwaDTO> rows, Integer sourceId) {
        Map<Long, BufferedImage> result = new HashMap<>();
        for (TrendingManhwaDTO row : rows) {
            String coverUrl = resolveCoverUrl(row, sourceId);
            result.put(row.getManhwaId(), loadCover(coverUrl));
        }
        return result;
    }

    private BufferedImage loadCover(String url) {
        if (url == null || url.isBlank()) {
            return placeholderCover;
        }
        try (InputStream in = new URL(url).openStream()) {
            BufferedImage fetched = ImageIO.read(in);
            return fetched == null ? placeholderCover : fetched;
        } catch (IOException e) {
            return placeholderCover;
        }
    }

    private String resolveCoverUrl(TrendingManhwaDTO row, Integer requestedSourceId) {
        String originalUrl = row.getCoverImageUrl();
        if (originalUrl == null || originalUrl.isBlank()) {
            return originalUrl;
        }
        if (!isAsuraRow(row, requestedSourceId)) {
            return originalUrl;
        }
        if (originalUrl.contains("/covers/asura/")) {
            return originalUrl;
        }
        Optional<String> cachedUrl = localCoverStorageService.storeCover(
                row.getManhwaId(),
                TitleSource.ASURA,
                originalUrl
        );
        return cachedUrl.orElse(null);
    }

    private boolean isAsuraRow(TrendingManhwaDTO row, Integer requestedSourceId) {
        if (requestedSourceId != null) {
            return requestedSourceId == 2;
        }
        String readUrl = row.getReadUrl();
        return readUrl != null && readUrl.contains("asuracomic.net");
    }

    private String describeMetricValue(TrendingManhwaDTO row) {
        return switch (row.getRankingMode()) {
            case RATE -> "Growth/day: " + (row.getGrowthPerDay() == null ? "-" : ("+" + DECIMAL_FORMAT.format(row.getGrowthPerDay())));
            case ABS -> "Absolute growth: " + (row.getGrowth() == null ? "-" : ("+" + INTEGER_FORMAT.format(row.getGrowth())));
            case PCT -> "Percent growth: " + (row.getGrowthPercent() == null ? "-" : ("+" + DECIMAL_FORMAT.format(row.getGrowthPercent() * 100) + "%"));
            case TOTAL -> "Total: " + INTEGER_FORMAT.format(row.getLatestValue());
            case ENGAGEMENT -> "Engagement score: " + (row.getRankingScore() == null ? "-" : DECIMAL_FORMAT.format(row.getRankingScore()));
            case ACCELERATION -> "Acceleration: " + (row.getRankingScore() == null ? "-" : DECIMAL_FORMAT.format(row.getRankingScore()));
        };
    }

    private String resolveSource(Integer sourceId) {
        if (sourceId == null) {
            return "All sources";
        }
        return switch (sourceId) {
            case 1 -> "Webtoons";
            case 2 -> "Asura";
            case 3 -> "Tapas";
            default -> "Source " + sourceId;
        };
    }

    private void applyRenderingHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private Font pickTitleFont(Graphics2D g, String text, int maxWidth, int maxLines) {
        for (int size = 68; size >= 38; size -= 2) {
            Font candidate = new Font("SansSerif", Font.BOLD, size);
            g.setFont(candidate);
            if (wrapLines(g.getFontMetrics(), safeTitle(text), maxWidth).size() <= maxLines) {
                return candidate;
            }
        }
        return new Font("SansSerif", Font.BOLD, 38);
    }

    private void drawWrappedText(Graphics2D g, String text, int x, int y, int maxWidth, int lineHeight, int maxLines) {
        List<String> lines = wrapLines(g.getFontMetrics(), text, maxWidth);
        int drawn = Math.min(maxLines, lines.size());
        for (int i = 0; i < drawn; i++) {
            g.drawString(lines.get(i), x, y + (i * lineHeight));
        }
    }

    private List<String> wrapLines(FontMetrics metrics, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(next) <= maxWidth) {
                line.setLength(0);
                line.append(next);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
                continue;
            }
            lines.add(word);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private String safeTitle(String text) {
        if (text == null || text.isBlank()) {
            return "Untitled";
        }
        return text;
    }

    private BufferedImage createFallbackCover() {
        int size = 240;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = img.createGraphics();
        graphics.setPaint(new GradientPaint(0, 0, new Color(46, 54, 82), size, size, new Color(29, 35, 62)));
        graphics.fillRect(0, 0, size, size);
        graphics.setColor(new Color(255, 255, 255, 170));
        graphics.setFont(new Font("SansSerif", Font.BOLD, 78));
        FontMetrics metrics = graphics.getFontMetrics();
        String letter = "M";
        int x = (size - metrics.stringWidth(letter)) / 2;
        int y = ((size - metrics.getHeight()) / 2) + metrics.getAscent();
        graphics.drawString(letter, x, y);
        graphics.dispose();
        return img;
    }
}
