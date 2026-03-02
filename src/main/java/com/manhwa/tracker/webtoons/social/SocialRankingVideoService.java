package com.manhwa.tracker.webtoons.social;

import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.model.RankingWindow;
import com.manhwa.tracker.webtoons.model.TitleSource;
import com.manhwa.tracker.webtoons.model.TrendingManhwaDTO;
import com.manhwa.tracker.webtoons.model.TrendingRankingMode;
import com.manhwa.tracker.webtoons.service.LocalCoverStorageService;
import com.manhwa.tracker.webtoons.service.TrendingService;
import org.jcodec.api.awt.AWTSequenceEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(SocialRankingVideoService.class);

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
        long startedAt = System.nanoTime();
        SocialRankingVideoRequest normalized = normalize(request);
        TimingSpec timing = resolveTiming(normalized.getPace());
        IntensitySpec intensity = resolveIntensity(normalized.getIntensity());

        List<TrendingManhwaDTO> rows = trendingService.getTrending(
                normalized.getMetric(),
                normalized.getSourceId(),
                normalized.getLimit(),
                normalized.getMode(),
                normalized.getWindow(),
                normalized.getGenre(),
                normalized.getMinPreviousValue()
        );
        if (rows.size() > normalized.getLimit()) {
            rows = rows.subList(0, normalized.getLimit());
        }

        RenderStats stats = new RenderStats();
        HookSpec hook = resolveHook(normalized, rows);
        SafeAreaSpec safeArea = resolveSafeArea(normalized.getFormat());
        LayoutSpec layout = resolveLayout(normalized.getFormat());
        ThemeSpec theme = resolveTheme(normalized.getTheme(), normalized.getVariant());
        Map<Long, BufferedImage> covers = preloadCovers(rows, normalized.getSourceId(), stats);

        Path tempVideo = Files.createTempFile("social-ranking-", ".mp4");
        try {
            AWTSequenceEncoder encoder = AWTSequenceEncoder.createSequenceEncoder(tempVideo.toFile(), FPS);
            for (int frame = 0; frame < timing.totalFrames(); frame++) {
                BufferedImage image = renderFrame(
                        normalized,
                        timing,
                        intensity,
                        hook,
                        theme,
                        safeArea,
                        layout,
                        rows,
                        covers,
                        frame,
                        stats
                );
                encoder.encodeImage(image);
            }
            encoder.finish();
            byte[] payload = Files.readAllBytes(tempVideo);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info(
                    "social-video telemetry mode={} window={} source={} format={} theme={} pace={} intensity={} variant={} hook={} rows={} renderMs={} fallbackCovers={} longestTitle={}",
                    normalized.getMode(),
                    normalized.getWindow(),
                    normalized.getSourceId(),
                    normalized.getFormat(),
                    normalized.getTheme(),
                    normalized.getPace(),
                    normalized.getIntensity(),
                    normalized.getVariant(),
                    hook.label(),
                    rows.size(),
                    elapsedMs,
                    stats.fallbackCovers,
                    stats.longestTitle
            );
            return payload;
        } finally {
            Files.deleteIfExists(tempVideo);
        }
    }

    private SocialRankingVideoRequest normalize(SocialRankingVideoRequest request) {
        SocialRankingVideoRequest normalized = new SocialRankingVideoRequest();
        MetricType requestedMetric = request.getMetric() == null ? MetricType.VIEWS : request.getMetric();
        normalized.setMetric(normalizeMetricForSource(requestedMetric, request.getSourceId()));
        normalized.setMode(request.getMode() == null ? TrendingRankingMode.RATE : request.getMode());
        normalized.setWindow(request.getWindow() == null ? RankingWindow.WEEKLY : request.getWindow());
        normalized.setSourceId(request.getSourceId());
        normalized.setGenre(request.getGenre());
        normalized.setMinPreviousValue(request.getMinPreviousValue());
        normalized.setTitle(request.getTitle());
        normalized.setSubtitle(request.getSubtitle());
        normalized.setIncludeTimestamp(request.getIncludeTimestamp() == null ? Boolean.TRUE : request.getIncludeTimestamp());
        normalized.setTheme(sanitizeTheme(request.getTheme()));
        normalized.setFormat(sanitizeFormat(request.getFormat()));
        normalized.setPace(sanitizePace(request.getPace()));
        normalized.setIntensity(sanitizeIntensity(request.getIntensity()));
        normalized.setCtaHandle(sanitizeHandle(request.getCtaHandle()));
        normalized.setCtaText(sanitizeCtaText(request.getCtaText(), normalized.getWindow()));
        normalized.setCampaignTag(sanitizeCampaignTag(request.getCampaignTag()));
        normalized.setVariant(sanitizeVariant(request.getVariant(), normalized));

        int limit = switch (normalized.getFormat()) {
            case "x" -> 3;
            case "tiktok" -> 4;
            default -> normalized.getSourceId() == null ? 5 : 4;
        };
        int requestedLimit = request.getLimit() == null ? limit : request.getLimit();
        normalized.setLimit(Math.max(3, Math.min(requestedLimit, 5)));
        return normalized;
    }

    private BufferedImage renderFrame(
            SocialRankingVideoRequest request,
            TimingSpec timing,
            IntensitySpec intensity,
            HookSpec hook,
            ThemeSpec theme,
            SafeAreaSpec safeArea,
            LayoutSpec layout,
            List<TrendingManhwaDTO> rows,
            Map<Long, BufferedImage> covers,
            int frame,
            RenderStats stats
    ) {
        BufferedImage canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        applyRenderingHints(g);

        if (rows.isEmpty()) {
            drawBackground(g, theme);
            drawEmpty(g, theme, safeArea);
            g.dispose();
            return canvas;
        }

        if (frame < timing.introFrames()) {
            drawIntroFrame(g, request, hook, theme, safeArea, frame / (float) Math.max(1, timing.introFrames()));
            drawProgress(g, theme, safeArea, frame + 1, timing.totalFrames());
            drawFooter(g, request, theme, safeArea);
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
        stats.longestTitle = Math.max(stats.longestTitle, safeTitle(currentRow.getTitle()).length());

        BufferedImage currentCover = covers.getOrDefault(currentRow.getManhwaId(), placeholderCover);
        BufferedImage previousCover = covers.getOrDefault(previousRow.getManhwaId(), placeholderCover);

        if (sceneProgress < timing.teaseWindow()) {
            drawBackground(g, theme);
            float tease = sceneProgress / timing.teaseWindow();
            drawRankTeaseOverlay(g, currentRowIndex + 1, hook, request, theme, safeArea, tease);
        } else {
            float revealStart = timing.teaseWindow();
            float revealEnd = 1f - timing.holdWindow();
            float revealProgress;
            if (sceneProgress >= revealEnd) {
                revealProgress = 1f;
            } else {
                revealProgress = (sceneProgress - revealStart) / Math.max(0.001f, (revealEnd - revealStart));
            }
            drawBackground(g, theme);
            if (scene > 0 && revealProgress < timing.transitionWindow()) {
                float t = revealProgress / timing.transitionWindow();
                drawCoverLayer(g, previousCover, -intensity.transitionOffset() * t, intensity.baseZoom() + (0.02f * t), 1f - t);
                drawCoverLayer(g, currentCover, intensity.transitionOffset() * (1f - t), intensity.baseZoom() + 0.03f - (0.03f * t), t);
                drawSceneOverlay(g, previousRow, previousRowIndex + 1, request, theme, safeArea, layout, 1f - t, false);
                drawSceneOverlay(g, currentRow, currentRowIndex + 1, request, theme, safeArea, layout, t, currentRowIndex == 0);
            } else {
                float zoom = intensity.baseZoom() + (intensity.zoomPulse() * (1f - revealProgress));
                drawCoverLayer(g, currentCover, 0f, zoom, 1f);
                float overlayAlpha = Math.min(1f, Math.max(0f, (revealProgress - 0.03f) / 0.18f));
                drawSceneOverlay(g, currentRow, currentRowIndex + 1, request, theme, safeArea, layout, overlayAlpha, currentRowIndex == 0);
            }
        }

        drawProgress(g, theme, safeArea, frame + 1, timing.totalFrames());
        drawFooter(g, request, theme, safeArea);
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

    private void drawIntroFrame(
            Graphics2D g,
            SocialRankingVideoRequest request,
            HookSpec hook,
            ThemeSpec theme,
            SafeAreaSpec safeArea,
            float progress
    ) {
        drawBackground(g, theme);
        float eased = (float) (1 - Math.pow(1 - progress, 3));
        int rise = Math.round((1f - eased) * 80f);
        float alpha = Math.max(0f, Math.min(1f, eased * 1.2f));

        AlphaComposite old = (AlphaComposite) g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

        int boxX = safeArea.sideInset();
        int boxY = safeArea.topInset() + 140 + rise;
        int boxW = WIDTH - (2 * safeArea.sideInset());
        g.setColor(withAlpha(theme.cardBackground(), 200));
        g.fill(new RoundRectangle2D.Double(boxX, boxY, boxW, 400, 40, 40));

        g.setColor(theme.primaryText());
        g.setFont(new Font("SansSerif", Font.BOLD, 74));
        String title = request.getTitle() == null || request.getTitle().isBlank() ? hook.headline() : request.getTitle();
        g.drawString(ellipsize(title, 26), boxX + 30, boxY + 138);

        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        g.setColor(theme.secondaryText());
        String subtitle = request.getSubtitle() == null || request.getSubtitle().isBlank()
                ? hook.subline()
                : request.getSubtitle();
        g.drawString(ellipsize(subtitle, 52), boxX + 34, boxY + 194);

        g.setFont(new Font("SansSerif", Font.PLAIN, 30));
        g.setColor(withAlpha(theme.primaryText(), 220));
        g.drawString(
                "Metric: " + request.getMetric().name() + " | " + resolveSource(request.getSourceId()) + " | " + request.getWindow().name(),
                boxX + 34,
                boxY + 252
        );

        g.setFont(new Font("SansSerif", Font.BOLD, 118));
        g.setColor(withAlpha(theme.accent(), 235));
        g.drawString("#" + request.getLimit(), boxX + 34, boxY + 386);

        g.setComposite(old);
    }

    private void drawRankTeaseOverlay(
            Graphics2D g,
            int rank,
            HookSpec hook,
            SocialRankingVideoRequest request,
            ThemeSpec theme,
            SafeAreaSpec safeArea,
            float progress
    ) {
        float eased = (float) (1 - Math.pow(1 - progress, 3));

        AlphaComposite old = (AlphaComposite) g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.86f));
        g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 130), 0, HEIGHT, new Color(0, 0, 0, 210)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setComposite(old);

        int rise = Math.round((1f - eased) * 36f);
        int left = safeArea.sideInset() + 16;
        g.setColor(theme.secondaryText());
        g.setFont(new Font("SansSerif", Font.BOLD, 50));
        g.drawString(hook.teaseLabel(), left, safeArea.topInset() + 188 + rise);

        g.setColor(theme.accent());
        g.setFont(new Font("SansSerif", Font.BOLD, 252));
        g.drawString("#" + rank, left, safeArea.topInset() + 452 + rise);

        g.setColor(theme.primaryText());
        g.setFont(new Font("SansSerif", Font.BOLD, 42));
        g.drawString("Who takes this spot?", left, safeArea.topInset() + 548 + rise);

        g.setColor(theme.secondaryText());
        g.setFont(new Font("SansSerif", Font.PLAIN, 31));
        g.drawString(resolveSource(request.getSourceId()), left, safeArea.topInset() + 596 + rise);
    }

    private void drawSceneOverlay(
            Graphics2D g,
            TrendingManhwaDTO row,
            int rank,
            SocialRankingVideoRequest request,
            ThemeSpec theme,
            SafeAreaSpec safeArea,
            LayoutSpec layout,
            float alpha,
            boolean finalRank
    ) {
        AlphaComposite old = (AlphaComposite) g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));

        g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 115), 0, HEIGHT, new Color(0, 0, 0, 215)));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        int left = safeArea.sideInset();
        int badgeY = safeArea.topInset() + 20;
        g.setColor(theme.badgeColor());
        g.fillRoundRect(left, badgeY, 170, 78, 22, 22);
        g.setColor(theme.badgeTextColor());
        g.setFont(new Font("SansSerif", Font.BOLD, 48));
        g.drawString("#" + rank, left + 34, badgeY + 56);

        g.setColor(theme.primaryText());
        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        String headerTitle = request.getTitle() == null || request.getTitle().isBlank() ? "Top Trending Manhwa" : request.getTitle();
        g.drawString(ellipsize(headerTitle, 34), left + 202, badgeY + 52);

        int titleX = left + 2;
        int titleY = safeArea.topInset() + 276;
        int maxWidth = WIDTH - (2 * safeArea.sideInset());
        int maxLines = layout.maxTitleLines();
        Font titleFont = pickTitleFont(g, row.getTitle(), maxWidth, maxLines);
        g.setFont(titleFont);
        g.setColor(theme.primaryText());
        drawWrappedText(g, safeTitle(row.getTitle()), titleX, titleY, maxWidth, titleFont.getSize() + 10, maxLines);

        int trendBoxY = HEIGHT - safeArea.bottomInset() - 236;
        g.setColor(withAlpha(theme.cardBackground(), 215));
        g.fill(new RoundRectangle2D.Double(left - 4, trendBoxY, WIDTH - (2 * safeArea.sideInset()) + 8, 122, 24, 24));
        g.setColor(withAlpha(theme.cardBorder(), 210));
        g.draw(new RoundRectangle2D.Double(left - 4, trendBoxY, WIDTH - (2 * safeArea.sideInset()) + 8, 122, 24, 24));

        g.setColor(theme.secondaryText());
        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        g.drawString("TREND", left + 26, trendBoxY + 44);

        g.setColor(theme.accent());
        g.setFont(new Font("SansSerif", Font.BOLD, 54));
        g.drawString(describeMetricValue(row), left + 24, trendBoxY + 96);

        if (finalRank) {
            g.setColor(withAlpha(theme.highlight(), 230));
            g.setFont(new Font("SansSerif", Font.BOLD, 30));
            g.drawString("NEW LEADER", WIDTH - safeArea.sideInset() - 240, trendBoxY + 42);
        }

        if (request.getIncludeTimestamp()) {
            g.setFont(new Font("SansSerif", Font.PLAIN, 24));
            String ts = "Generated " + LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            FontMetrics metrics = g.getFontMetrics();
            int width = metrics.stringWidth(ts);
            g.setColor(withAlpha(theme.primaryText(), 190));
            g.drawString(ts, WIDTH - safeArea.sideInset() - width, HEIGHT - safeArea.bottomInset() - 24);
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

    private void drawProgress(Graphics2D g, ThemeSpec theme, SafeAreaSpec safeArea, int frame, int totalFrames) {
        int barX = safeArea.sideInset();
        int barY = HEIGHT - safeArea.bottomInset() - 78;
        int barW = WIDTH - (2 * safeArea.sideInset());
        int barH = 14;
        float progress = Math.max(0f, Math.min(1f, frame / (float) totalFrames));

        g.setColor(withAlpha(theme.primaryText(), 56));
        g.fillRoundRect(barX, barY, barW, barH, 16, 16);
        g.setColor(theme.accent());
        g.fillRoundRect(barX, barY, Math.round(barW * progress), barH, 16, 16);
    }

    private void drawFooter(Graphics2D g, SocialRankingVideoRequest request, ThemeSpec theme, SafeAreaSpec safeArea) {
        int footerY = HEIGHT - safeArea.bottomInset() - 22;
        g.setColor(withAlpha(theme.primaryText(), 188));
        g.setFont(new Font("SansSerif", Font.PLAIN, 24));
        String left = request.getCtaText() + " " + request.getCtaHandle() + " " + request.getCampaignTag();
        g.drawString(ellipsize(left, 72), safeArea.sideInset(), footerY);

        String right = "v=" + request.getVariant() + " | " + request.getFormat();
        FontMetrics metrics = g.getFontMetrics();
        int width = metrics.stringWidth(right);
        g.drawString(right, WIDTH - safeArea.sideInset() - width, footerY);
    }

    private void drawEmpty(Graphics2D g, ThemeSpec theme, SafeAreaSpec safeArea) {
        g.setColor(withAlpha(theme.primaryText(), 222));
        g.setFont(new Font("SansSerif", Font.BOLD, 46));
        g.drawString("No ranking data available.", safeArea.sideInset(), HEIGHT / 2 - 20);
        g.setFont(new Font("SansSerif", Font.PLAIN, 30));
        g.setColor(withAlpha(theme.secondaryText(), 220));
        g.drawString("Run scrape jobs, then retry /api/social-ranking.mp4", safeArea.sideInset(), HEIGHT / 2 + 34);
    }

    private Map<Long, BufferedImage> preloadCovers(List<TrendingManhwaDTO> rows, Integer sourceId, RenderStats stats) {
        Map<Long, BufferedImage> result = new HashMap<>();
        for (TrendingManhwaDTO row : rows) {
            String coverUrl = resolveCoverUrl(row, sourceId);
            result.put(row.getManhwaId(), loadCover(coverUrl, stats));
        }
        return result;
    }

    private BufferedImage loadCover(String url, RenderStats stats) {
        if (url == null || url.isBlank()) {
            stats.fallbackCovers++;
            return placeholderCover;
        }
        try (InputStream in = new URL(url).openStream()) {
            BufferedImage fetched = ImageIO.read(in);
            if (fetched == null) {
                stats.fallbackCovers++;
                return placeholderCover;
            }
            return fetched;
        } catch (IOException e) {
            stats.fallbackCovers++;
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
            case ACCELERATION -> "Heating Up " + (row.getRankingScore() == null ? "-" : DECIMAL_FORMAT.format(row.getRankingScore()));
            case SOCIAL -> "Social Score " + (row.getRankingScore() == null ? "-" : DECIMAL_FORMAT.format(row.getRankingScore()));
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

    private String ellipsize(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "Untitled";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    private HookSpec resolveHook(SocialRankingVideoRequest request, List<TrendingManhwaDTO> rows) {
        if (request.getMode() == TrendingRankingMode.PCT && !rows.isEmpty()) {
            Double pct = rows.get(0).getGrowthPercent();
            if (pct != null && pct > 0.45d) {
                return new HookSpec("Breakout Alert", "Huge relative move detected", "BREAKOUT WATCH");
            }
        }
        return switch (request.getMode()) {
            case RATE -> new HookSpec(
                    request.getWindow() == RankingWindow.DAILY ? "Fastest Rising Today" : "Weekly Momentum Leaders",
                    "Who is climbing fastest right now?",
                    "NEXT MOVER"
            );
            case ABS -> new HookSpec("Biggest Net Gainers", "Largest absolute growth this window", "BIG GAIN");
            case PCT -> new HookSpec("Breakout of the Window", "Relative growth leaders are in", "NEXT BREAKOUT");
            case ACCELERATION -> new HookSpec("Heating Up Fast", "Momentum is speeding up now", "NEXT HEAT CHECK");
            case ENGAGEMENT -> new HookSpec("Fan Efficiency Board", "Where fandom impact is strongest", "NEXT FAN FAVORITE");
            case TOTAL -> new HookSpec("Most Followed Right Now", "Authority board across top titles", "NEXT POWER TITLE");
            case SOCIAL -> new HookSpec("Editor's Social Rank", "Composite social signal leaderboard", "NEXT SOCIAL PICK");
        };
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
            case "tiktok", "instagram", "x", "reels" -> normalized;
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

    private String sanitizeIntensity(String intensity) {
        if (intensity == null || intensity.isBlank()) {
            return "standard";
        }
        String normalized = intensity.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "calm", "standard", "hype" -> normalized;
            default -> "standard";
        };
    }

    private String sanitizeHandle(String handle) {
        if (handle == null || handle.isBlank()) {
            return "@manhwa.tracker";
        }
        return handle.trim();
    }

    private String sanitizeCtaText(String ctaText, RankingWindow window) {
        if (ctaText == null || ctaText.isBlank()) {
            return window == RankingWindow.DAILY ? "Follow for daily movers" : "Follow for weekly winners";
        }
        return ctaText.trim();
    }

    private String sanitizeCampaignTag(String campaignTag) {
        if (campaignTag == null || campaignTag.isBlank()) {
            return "#manhwa";
        }
        return campaignTag.trim();
    }

    private String sanitizeVariant(String variant, SocialRankingVideoRequest request) {
        if (variant != null && !variant.isBlank()) {
            String normalized = variant.trim().toUpperCase(Locale.ROOT);
            if ("A".equals(normalized) || "B".equals(normalized)) {
                return normalized;
            }
        }
        String key = request.getMetric().name()
                + "|" + request.getMode().name()
                + "|" + request.getWindow().name()
                + "|" + (request.getSourceId() == null ? "ALL" : request.getSourceId())
                + "|" + request.getFormat();
        return Math.abs(key.hashCode()) % 2 == 0 ? "A" : "B";
    }

    private MetricType normalizeMetricForSource(MetricType metricType, Integer sourceId) {
        if (metricType == null || sourceId == null) {
            return metricType == null ? MetricType.VIEWS : metricType;
        }
        if (sourceId == 2) {
            if (metricType == MetricType.VIEWS || metricType == MetricType.SUBSCRIBERS || metricType == MetricType.LIKES) {
                return MetricType.FOLLOWERS;
            }
            return metricType;
        }
        if (metricType == MetricType.FOLLOWERS) {
            return sourceId == 3 ? MetricType.SUBSCRIBERS : MetricType.VIEWS;
        }
        return metricType;
    }

    private TimingSpec resolveTiming(String pace) {
        if ("fast".equals(pace)) {
            // Faster profile, but still long enough to read each title and pause briefly.
            return new TimingSpec(12, Math.round(FPS * 0.8f), 0.15f, 0.24f, 0.24f);
        }
        // Standard profile optimized for readability with longer hold on each screen.
        return new TimingSpec(18, Math.round(FPS * 1.1f), 0.16f, 0.24f, 0.32f);
    }

    private IntensitySpec resolveIntensity(String intensity) {
        return switch (intensity) {
            case "calm" -> new IntensitySpec(1.01f, 0.018f, 72f);
            case "hype" -> new IntensitySpec(1.06f, 0.05f, 162f);
            default -> new IntensitySpec(1.03f, 0.03f, 120f);
        };
    }

    private ThemeSpec resolveTheme(String themeName, String variant) {
        ThemeSpec base = switch (themeName) {
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
        if ("B".equals(variant)) {
            return new ThemeSpec(
                    base.backgroundTop(),
                    base.backgroundBottom(),
                    base.highlight(),
                    base.accent(),
                    base.cardBackground(),
                    base.cardBorder(),
                    base.primaryText(),
                    base.secondaryText(),
                    base.badgeColor(),
                    base.badgeTextColor()
            );
        }
        return base;
    }

    private SafeAreaSpec resolveSafeArea(String format) {
        return switch (format) {
            case "tiktok" -> new SafeAreaSpec(120, 210, 72);
            case "reels" -> new SafeAreaSpec(104, 172, 68);
            case "x" -> new SafeAreaSpec(72, 126, 94);
            default -> new SafeAreaSpec(92, 150, 72);
        };
    }

    private LayoutSpec resolveLayout(String format) {
        return switch (format) {
            case "x" -> new LayoutSpec(5);
            case "tiktok" -> new LayoutSpec(7);
            default -> new LayoutSpec(8);
        };
    }

    private Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static class RenderStats {
        private int fallbackCovers = 0;
        private int longestTitle = 0;
    }

    private record HookSpec(String headline, String subline, String teaseLabel) {
        private String label() {
            return headline;
        }
    }

    private record TimingSpec(
            int durationSeconds,
            int introFrames,
            float teaseWindow,
            float transitionWindow,
            float holdWindow
    ) {
        private int totalFrames() {
            return FPS * durationSeconds;
        }
    }

    private record IntensitySpec(float baseZoom, float zoomPulse, float transitionOffset) {
    }

    private record SafeAreaSpec(int topInset, int bottomInset, int sideInset) {
    }

    private record LayoutSpec(int maxTitleLines) {
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
