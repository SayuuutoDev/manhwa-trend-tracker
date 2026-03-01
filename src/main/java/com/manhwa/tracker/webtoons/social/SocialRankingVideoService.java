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
        TimingSpec timing = resolveTiming(normalized.getPace());

        List<TrendingManhwaDTO> rows = trendingService.getTrending(
                normalized.getMetric(),
                normalized.getSourceId(),
                normalized.getLimit(),
                normalized.getMode(),
                null
        );
        if (rows.size() > normalized.getLimit()) {
            rows = rows.subList(0, normalized.getLimit());
        }

        Map<Long, BufferedImage> covers = preloadCovers(rows, normalized.getSourceId());
        Path tempVideo = Files.createTempFile("social-ranking-", ".mp4");
        try {
            AWTSequenceEncoder encoder = AWTSequenceEncoder.createSequenceEncoder(tempVideo.toFile(), FPS);
            for (int frame = 0; frame < timing.totalFrames(); frame++) {
                BufferedImage image = renderFrame(normalized, timing, rows, covers, frame);
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
        normalized.setTheme(sanitizeTheme(request.getTheme()));
        normalized.setFormat(sanitizeFormat(request.getFormat()));
        normalized.setPace(sanitizePace(request.getPace()));

        int limit = request.getSourceId() == null ? 5 : 4;
        if ("x".equals(normalized.getFormat())) {
            limit = 3;
        }
        if ("tiktok".equals(normalized.getFormat())) {
            limit = 4;
        }
        int requestedLimit = request.getLimit() == null ? limit : request.getLimit();
        normalized.setLimit(Math.max(3, Math.min(requestedLimit, 5)));
        return normalized;
    }

    private BufferedImage renderFrame(
            SocialRankingVideoRequest request,
            TimingSpec timing,
            List<TrendingManhwaDTO> rows,
            Map<Long, BufferedImage> covers,
            int frame
    ) {
        ThemeSpec theme = resolveTheme(request.getTheme());
        BufferedImage canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        applyRenderingHints(g);

        if (rows.isEmpty()) {
            drawBackground(g, theme);
            drawEmpty(g, theme);
            g.dispose();
            return canvas;
        }

        if (frame < timing.introFrames()) {
            drawIntroFrame(g, request, theme, frame / (float) Math.max(1, timing.introFrames()));
            drawProgress(g, theme, frame + 1, timing.totalFrames());
            drawFooter(g, request, theme);
            g.dispose();
            return canvas;
        }

        int mainFrames = timing.totalFrames() - timing.introFrames();
        int sceneFrames = Math.max(1, mainFrames / rows.size());
        int mainFrame = frame - timing.introFrames();
        int scene = Math.min(rows.size() - 1, mainFrame / sceneFrames);
        int currentRowIndex = rows.size() - 1 - scene;
        int sceneFrame = mainFrame - (scene * sceneFrames);
        float sceneProgress = Math.max(0f, Math.min(1f, sceneFrame / (float) sceneFrames));

        int previousScene = Math.max(0, scene - 1);
        int previousRowIndex = rows.size() - 1 - previousScene;
        TrendingManhwaDTO currentRow = rows.get(currentRowIndex);
        TrendingManhwaDTO previousRow = rows.get(previousRowIndex);
        BufferedImage currentCover = covers.getOrDefault(currentRow.getManhwaId(), placeholderCover);
        BufferedImage previousCover = covers.getOrDefault(previousRow.getManhwaId(), placeholderCover);

        if (sceneProgress < timing.teaseWindow()) {
            drawBackground(g, theme);
            float tease = sceneProgress / timing.teaseWindow();
            drawRankTeaseOverlay(g, currentRowIndex + 1, request, theme, tease);
        } else {
            float revealProgress = (sceneProgress - timing.teaseWindow()) / (1f - timing.teaseWindow());
            drawBackground(g, theme);
            if (scene > 0 && revealProgress < timing.transitionWindow()) {
                float t = revealProgress / timing.transitionWindow();
                drawCoverLayer(g, previousCover, -120f * t, 1.03f + (0.02f * t), 1f - t);
                drawCoverLayer(g, currentCover, 120f * (1f - t), 1.06f - (0.04f * t), t);
                drawSceneOverlay(g, previousRow, previousRowIndex + 1, request, theme, 1f - t, false);
                drawSceneOverlay(g, currentRow, currentRowIndex + 1, request, theme, t, currentRowIndex == 0);
            } else {
                float zoom = 1.03f + (0.03f * (1f - revealProgress));
                drawCoverLayer(g, currentCover, 0f, zoom, 1f);
                float overlayAlpha = Math.min(1f, Math.max(0f, (revealProgress - 0.03f) / 0.18f));
                drawSceneOverlay(g, currentRow, currentRowIndex + 1, request, theme, overlayAlpha, currentRowIndex == 0);
            }
        }

        drawProgress(g, theme, frame + 1, timing.totalFrames());
        drawFooter(g, request, theme);
        g.dispose();
        return canvas;
    }

    private void drawBackground(Graphics2D g, ThemeSpec theme) {
        g.setPaint(new GradientPaint(0, 0, theme.backgroundTop(), 0, HEIGHT, theme.backgroundBottom()));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(withAlpha(theme.accent(), 24));
        g.fillOval(-160, -220, 760, 760);
        g.setColor(withAlpha(theme.highlight(), 20));
        g.fillOval(540, -180, 760, 760);
    }

    private void drawIntroFrame(Graphics2D g, SocialRankingVideoRequest request, ThemeSpec theme, float progress) {
        drawBackground(g, theme);
        float eased = (float) (1 - Math.pow(1 - progress, 3));
        int rise = Math.round((1f - eased) * 80f);
        float alpha = Math.max(0f, Math.min(1f, eased * 1.2f));

        AlphaComposite old = (AlphaComposite) g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

        g.setColor(withAlpha(theme.cardBackground(), 200));
        g.fill(new RoundRectangle2D.Double(60, 250 + rise, WIDTH - 120, 380, 40, 40));

        g.setColor(theme.primaryText());
        g.setFont(new Font("SansSerif", Font.BOLD, 78));
        String title = request.getTitle() == null || request.getTitle().isBlank() ? "Top Trending Manhwa" : request.getTitle();
        g.drawString(title, 88, 392 + rise);

        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        g.setColor(theme.secondaryText());
        String subtitle = request.getSubtitle() == null || request.getSubtitle().isBlank()
                ? "Fast ranking reveal starts now"
                : request.getSubtitle();
        g.drawString(subtitle, 92, 450 + rise);

        g.setFont(new Font("SansSerif", Font.PLAIN, 30));
        g.setColor(withAlpha(theme.primaryText(), 220));
        g.drawString("Metric: " + request.getMetric().name() + " | " + resolveSource(request.getSourceId()), 92, 508 + rise);

        g.setFont(new Font("SansSerif", Font.BOLD, 124));
        g.setColor(withAlpha(theme.accent(), 235));
        g.drawString("#" + request.getLimit(), 92, 770 + Math.round((1f - eased) * 26f));

        g.setComposite(old);
    }

    private void drawRankTeaseOverlay(Graphics2D g, int rank, SocialRankingVideoRequest request, ThemeSpec theme, float progress) {
        float eased = (float) (1 - Math.pow(1 - progress, 3));

        AlphaComposite old = (AlphaComposite) g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.86f));
        g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 130), 0, HEIGHT, new Color(0, 0, 0, 210)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setComposite(old);

        int rise = Math.round((1f - eased) * 36f);
        g.setColor(theme.secondaryText());
        g.setFont(new Font("SansSerif", Font.BOLD, 52));
        g.drawString("NEXT RANK", 78, 272 + rise);

        g.setColor(theme.accent());
        g.setFont(new Font("SansSerif", Font.BOLD, 260));
        g.drawString("#" + rank, 78, 526 + rise);

        g.setColor(theme.primaryText());
        g.setFont(new Font("SansSerif", Font.BOLD, 44));
        g.drawString("Who takes this spot?", 78, 614 + rise);

        g.setColor(theme.secondaryText());
        g.setFont(new Font("SansSerif", Font.PLAIN, 32));
        g.drawString(resolveSource(request.getSourceId()), 78, 664 + rise);
    }

    private void drawSceneOverlay(
            Graphics2D g,
            TrendingManhwaDTO row,
            int rank,
            SocialRankingVideoRequest request,
            ThemeSpec theme,
            float alpha,
            boolean finalRank
    ) {
        AlphaComposite old = (AlphaComposite) g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));

        g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 115), 0, HEIGHT, new Color(0, 0, 0, 215)));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        int badgeY = 88;
        g.setColor(theme.badgeColor());
        g.fillRoundRect(72, badgeY, 170, 78, 22, 22);
        g.setColor(theme.badgeTextColor());
        g.setFont(new Font("SansSerif", Font.BOLD, 48));
        g.drawString("#" + rank, 106, badgeY + 56);

        g.setColor(theme.primaryText());
        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        String headerTitle = request.getTitle() == null || request.getTitle().isBlank() ? "Top Trending Manhwa" : request.getTitle();
        g.drawString(headerTitle, 274, 140);

        int titleX = 74;
        int titleY = 360;
        int maxWidth = WIDTH - 148;
        int maxLines = 8;
        Font titleFont = pickTitleFont(g, row.getTitle(), maxWidth, maxLines);
        g.setFont(titleFont);
        g.setColor(theme.primaryText());
        drawWrappedText(g, safeTitle(row.getTitle()), titleX, titleY, maxWidth, titleFont.getSize() + 10, maxLines);

        g.setColor(withAlpha(theme.cardBackground(), 215));
        g.fill(new RoundRectangle2D.Double(62, HEIGHT - 312, WIDTH - 124, 116, 24, 24));
        g.setColor(withAlpha(theme.cardBorder(), 210));
        g.draw(new RoundRectangle2D.Double(62, HEIGHT - 312, WIDTH - 124, 116, 24, 24));

        g.setColor(theme.secondaryText());
        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        g.drawString("TREND", 92, HEIGHT - 266);

        g.setColor(theme.accent());
        g.setFont(new Font("SansSerif", Font.BOLD, 56));
        g.drawString(describeMetricValue(row), 92, HEIGHT - 214);

        if (finalRank) {
            g.setColor(withAlpha(theme.highlight(), 220));
            g.setFont(new Font("SansSerif", Font.BOLD, 30));
            g.drawString("NEW LEADER", WIDTH - 294, HEIGHT - 266);
        }

        if (request.getIncludeTimestamp()) {
            g.setFont(new Font("SansSerif", Font.PLAIN, 24));
            String ts = "Generated " + LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            FontMetrics metrics = g.getFontMetrics();
            int width = metrics.stringWidth(ts);
            g.setColor(withAlpha(theme.primaryText(), 190));
            g.drawString(ts, WIDTH - width - 74, HEIGHT - 56);
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

    private void drawProgress(Graphics2D g, ThemeSpec theme, int frame, int totalFrames) {
        int barX = 66;
        int barY = HEIGHT - 144;
        int barW = WIDTH - 132;
        int barH = 14;
        float progress = Math.max(0f, Math.min(1f, frame / (float) totalFrames));

        g.setColor(withAlpha(theme.primaryText(), 56));
        g.fillRoundRect(barX, barY, barW, barH, 16, 16);
        g.setColor(theme.accent());
        g.fillRoundRect(barX, barY, Math.round(barW * progress), barH, 16, 16);
    }

    private void drawFooter(Graphics2D g, SocialRankingVideoRequest request, ThemeSpec theme) {
        g.setColor(withAlpha(theme.primaryText(), 182));
        g.setFont(new Font("SansSerif", Font.PLAIN, 23));
        g.drawString("Source: " + resolveSource(request.getSourceId()) + " | Follow for weekly updates", 68, HEIGHT - 92);
    }

    private void drawEmpty(Graphics2D g, ThemeSpec theme) {
        g.setColor(withAlpha(theme.primaryText(), 222));
        g.setFont(new Font("SansSerif", Font.BOLD, 46));
        g.drawString("No ranking data available.", 120, HEIGHT / 2 - 20);
        g.setFont(new Font("SansSerif", Font.PLAIN, 30));
        g.setColor(withAlpha(theme.secondaryText(), 220));
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
            case RATE -> row.getGrowthPerDay() == null ? "Growth/day -" : ("Growth/day +" + DECIMAL_FORMAT.format(row.getGrowthPerDay()));
            case ABS -> row.getGrowth() == null ? "Growth -" : ("Growth +" + INTEGER_FORMAT.format(row.getGrowth()));
            case PCT -> row.getGrowthPercent() == null ? "Growth -" : ("Growth +" + DECIMAL_FORMAT.format(row.getGrowthPercent() * 100) + "%");
            case TOTAL -> "Total " + INTEGER_FORMAT.format(row.getLatestValue());
            case ENGAGEMENT -> "Engagement " + (row.getRankingScore() == null ? "-" : DECIMAL_FORMAT.format(row.getRankingScore()));
            case ACCELERATION -> "Acceleration " + (row.getRankingScore() == null ? "-" : DECIMAL_FORMAT.format(row.getRankingScore()));
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

    private String sanitizeTheme(String theme) {
        if (theme == null || theme.isBlank()) {
            return "clean";
        }
        String normalized = theme.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "clean", "neon", "dark" -> normalized;
            default -> "clean";
        };
    }

    private String sanitizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return "instagram";
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "tiktok", "instagram", "x" -> normalized;
            default -> "instagram";
        };
    }

    private String sanitizePace(String pace) {
        if (pace == null || pace.isBlank()) {
            return "standard";
        }
        String normalized = pace.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "fast", "standard" -> normalized;
            default -> "standard";
        };
    }

    private TimingSpec resolveTiming(String pace) {
        if ("fast".equals(pace)) {
            return new TimingSpec(8, Math.round(FPS * 0.5f), 0.15f, 0.20f);
        }
        return new TimingSpec(12, Math.round(FPS * 0.75f), 0.16f, 0.22f);
    }

    private ThemeSpec resolveTheme(String themeName) {
        return switch (themeName) {
            case "neon" -> new ThemeSpec(
                    new Color(15, 8, 38),
                    new Color(4, 7, 22),
                    new Color(56, 236, 255),
                    new Color(255, 96, 62),
                    new Color(24, 19, 58),
                    new Color(118, 238, 255),
                    new Color(255, 255, 255),
                    new Color(205, 233, 255),
                    new Color(56, 236, 255),
                    new Color(6, 20, 30)
            );
            case "dark" -> new ThemeSpec(
                    new Color(16, 18, 26),
                    new Color(8, 9, 14),
                    new Color(123, 170, 255),
                    new Color(255, 177, 89),
                    new Color(30, 33, 44),
                    new Color(132, 152, 184),
                    new Color(250, 252, 255),
                    new Color(208, 216, 232),
                    new Color(255, 177, 89),
                    new Color(30, 26, 14)
            );
            default -> new ThemeSpec(
                    new Color(19, 24, 48),
                    new Color(8, 10, 22),
                    new Color(80, 216, 255),
                    new Color(255, 114, 66),
                    new Color(20, 28, 56),
                    new Color(155, 244, 255),
                    new Color(255, 255, 255),
                    new Color(217, 251, 255),
                    new Color(80, 216, 255),
                    new Color(8, 20, 30)
            );
        };
    }

    private Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private record TimingSpec(int durationSeconds, int introFrames, float teaseWindow, float transitionWindow) {
        private int totalFrames() {
            return FPS * durationSeconds;
        }
    }

    private record ThemeSpec(
            Color backgroundTop,
            Color backgroundBottom,
            Color accent,
            Color highlight,
            Color cardBackground,
            Color cardBorder,
            Color primaryText,
            Color secondaryText,
            Color badgeColor,
            Color badgeTextColor
    ) {
    }
}
