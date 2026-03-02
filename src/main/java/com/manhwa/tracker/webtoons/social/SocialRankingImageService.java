package com.manhwa.tracker.webtoons.social;

import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.model.RankingWindow;
import com.manhwa.tracker.webtoons.model.TitleSource;
import com.manhwa.tracker.webtoons.model.TrendingManhwaDTO;
import com.manhwa.tracker.webtoons.model.TrendingRankingMode;
import com.manhwa.tracker.webtoons.service.LocalCoverStorageService;
import com.manhwa.tracker.webtoons.service.TrendingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class SocialRankingImageService {
    private static final Logger log = LoggerFactory.getLogger(SocialRankingImageService.class);

    private static final int IMAGE_WIDTH = 1080;
    private static final int IMAGE_HEIGHT = 1350;
    private static final DecimalFormat INTEGER_FORMAT = new DecimalFormat("#,###");
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#,###.0");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private final TrendingService trendingService;
    private final LocalCoverStorageService localCoverStorageService;
    private final BufferedImage placeholderCover;

    static {
        ImageIO.scanForPlugins();
    }

    public SocialRankingImageService(
            TrendingService trendingService,
            LocalCoverStorageService localCoverStorageService
    ) {
        this.trendingService = trendingService;
        this.localCoverStorageService = localCoverStorageService;
        this.placeholderCover = createFallbackCover();
    }

    public byte[] createImage(SocialRankingImageRequest request) throws IOException {
        long startedAt = System.nanoTime();
        SocialRankingImageRequest normalized = normalize(request);
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
        BufferedImage canvas = render(normalized, rows, stats);
        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(canvas, "png", baos);
            bytes = baos.toByteArray();
        }

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        log.info(
                "social-image telemetry mode={} window={} source={} format={} theme={} pace={} intensity={} variant={} rows={} renderMs={} fallbackCovers={} longestTitle={}",
                normalized.getMode(),
                normalized.getWindow(),
                normalized.getSourceId(),
                normalized.getFormat(),
                normalized.getTheme(),
                normalized.getPace(),
                normalized.getIntensity(),
                normalized.getVariant(),
                rows.size(),
                elapsedMs,
                stats.fallbackCovers,
                stats.longestTitle
        );

        return bytes;
    }

    private SocialRankingImageRequest normalize(SocialRankingImageRequest request) {
        SocialRankingImageRequest normalized = new SocialRankingImageRequest();
        MetricType requestedMetric = request.getMetric() == null ? MetricType.VIEWS : request.getMetric();
        normalized.setMetric(normalizeMetricForSource(requestedMetric, request.getSourceId()));
        normalized.setMode(request.getMode() == null ? TrendingRankingMode.RATE : request.getMode());
        normalized.setWindow(request.getWindow() == null ? RankingWindow.WEEKLY : request.getWindow());
        normalized.setSourceId(request.getSourceId());
        normalized.setGenre(request.getGenre());
        normalized.setMinPreviousValue(request.getMinPreviousValue());

        String format = sanitizeFormat(request.getFormat());
        int defaultLimit = switch (format) {
            case "x" -> 3;
            case "tiktok" -> 4;
            default -> 5;
        };
        int requestedLimit = request.getLimit() == null ? defaultLimit : request.getLimit();
        normalized.setLimit(Math.max(3, Math.min(requestedLimit, 5)));

        normalized.setTitle(request.getTitle());
        normalized.setSubtitle(request.getSubtitle());
        normalized.setIncludeTimestamp(request.getIncludeTimestamp() == null ? Boolean.TRUE : request.getIncludeTimestamp());
        normalized.setTheme(sanitizeTheme(request.getTheme()));
        normalized.setFormat(format);
        normalized.setPace(sanitizePace(request.getPace()));
        normalized.setIntensity(sanitizeIntensity(request.getIntensity()));
        normalized.setCtaHandle(sanitizeHandle(request.getCtaHandle()));
        normalized.setCtaText(sanitizeCtaText(request.getCtaText(), normalized.getWindow()));
        normalized.setCampaignTag(sanitizeCampaignTag(request.getCampaignTag()));
        normalized.setVariant(sanitizeVariant(request.getVariant(), normalized));
        return normalized;
    }

    private BufferedImage render(SocialRankingImageRequest request, List<TrendingManhwaDTO> rows, RenderStats stats) {
        SafeAreaSpec safeArea = resolveSafeArea(request.getFormat());
        LayoutSpec layout = adjustLayout(resolveLayout(request.getFormat()), safeArea, rows.size());
        ThemeSpec theme = resolveTheme(request.getTheme(), request.getVariant());

        BufferedImage canvas = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        applyRenderingHints(g);

        drawBackground(g, theme);
        drawHeader(g, request, theme, safeArea, layout, rows.isEmpty());
        int startY = safeArea.topInset() + layout.headerHeight();

        if (rows.isEmpty()) {
            drawEmptyState(g, startY, IMAGE_HEIGHT - startY - safeArea.bottomInset() - layout.footerHeight(), theme, safeArea);
        } else {
            for (int i = 0; i < rows.size(); i++) {
                int y = startY + i * (layout.entryHeight() + layout.entrySpacing());
                drawEntry(g, rows.get(i), i, y, request.getSourceId(), theme, safeArea, layout, stats);
            }
        }
        drawFooter(g, request, theme, safeArea, layout);

        g.dispose();
        return canvas;
    }

    private void drawBackground(Graphics2D g, ThemeSpec theme) {
        GradientPaint gradient = new GradientPaint(0, 0, theme.backgroundTop(), 0, IMAGE_HEIGHT, theme.backgroundBottom());
        g.setPaint(gradient);
        g.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        g.setColor(withAlpha(theme.accent(), 45));
        g.fill(new Ellipse2D.Double(-180, -100, 620, 620));
        g.setColor(withAlpha(theme.highlight(), 40));
        g.fill(new Ellipse2D.Double(620, -140, 560, 560));
        g.setColor(withAlpha(theme.cardBackground(), 75));
        g.fill(new RoundRectangle2D.Double(40, 30, IMAGE_WIDTH - 80, 190, 36, 36));
    }

    private void drawHeader(
            Graphics2D g,
            SocialRankingImageRequest request,
            ThemeSpec theme,
            SafeAreaSpec safeArea,
            LayoutSpec layout,
            boolean empty
    ) {
        Font titleFont = new Font("SansSerif", Font.BOLD, 56);
        Font subtitleFont = new Font("SansSerif", Font.BOLD, 26);
        Font metaFont = new Font("SansSerif", Font.PLAIN, 20);

        int leftX = safeArea.sideInset();
        int headerTop = safeArea.topInset();

        g.setColor(theme.primaryText());
        g.setFont(titleFont);
        String title = request.getTitle() == null || request.getTitle().isBlank()
                ? buildDefaultTitle(request)
                : request.getTitle();
        g.drawString(ellipsize(title, 38), leftX, headerTop + 88);

        String subtitle = request.getSubtitle();
        if (subtitle == null || subtitle.isBlank()) {
            subtitle = buildDefaultSubtitle(request);
        }
        g.setFont(subtitleFont);
        g.setColor(theme.secondaryText());
        g.drawString(ellipsize(subtitle, 58), leftX + 2, headerTop + 132);

        g.setFont(metaFont);
        g.setColor(withAlpha(theme.primaryText(), 220));
        String meta = request.getWindow().name() + " window | " + resolveSource(request.getSourceId());
        g.drawString(meta, leftX + 2, headerTop + 170);

        if (request.getIncludeTimestamp()) {
            String timestamp = "Generated " + LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            FontMetrics metrics = g.getFontMetrics(metaFont);
            int width = metrics.stringWidth(timestamp);
            g.drawString(timestamp, IMAGE_WIDTH - safeArea.sideInset() - width, headerTop + 46);
        }

        if (empty) {
            g.setColor(withAlpha(theme.primaryText(), 220));
            g.drawString("No ranking data available for this configuration.", leftX + 2, headerTop + layout.headerHeight() - 16);
        }
    }

    private void drawEmptyState(Graphics2D g, int startY, int availableHeight, ThemeSpec theme, SafeAreaSpec safeArea) {
        Font messageFont = new Font("SansSerif", Font.PLAIN, 24);
        g.setFont(messageFont);
        g.setColor(withAlpha(theme.secondaryText(), 180));
        g.drawString("Nothing to rank yet. Run a scrape job and try again.", safeArea.sideInset(), startY + availableHeight / 2);
    }

    private void drawEntry(
            Graphics2D g,
            TrendingManhwaDTO row,
            int index,
            int y,
            Integer requestedSourceId,
            ThemeSpec theme,
            SafeAreaSpec safeArea,
            LayoutSpec layout,
            RenderStats stats
    ) {
        stats.longestTitle = Math.max(stats.longestTitle, row.getTitle() == null ? 0 : row.getTitle().length());

        int cardX = safeArea.sideInset();
        int cardWidth = IMAGE_WIDTH - (2 * safeArea.sideInset());
        RoundRectangle2D card = new RoundRectangle2D.Double(cardX, y, cardWidth, layout.entryHeight(), 30, 30);
        g.setColor(withAlpha(theme.cardBackground(), 215));
        g.fill(card);
        g.setColor(withAlpha(theme.cardBorder(), 180));
        g.setStroke(new BasicStroke(2f));
        g.draw(card);

        int coverSize = layout.entryHeight() - 26;
        int coverX = cardX + 22;
        int coverY = y + (layout.entryHeight() - coverSize) / 2;
        BufferedImage cover = loadCover(resolveCoverUrl(row, requestedSourceId), stats);
        g.drawImage(cover, coverX, coverY, coverSize, coverSize, null);

        int badgeX = coverX + coverSize + 20;
        int badgeY = coverY + 6;
        g.setColor(theme.badgeColor());
        g.fillRoundRect(badgeX, badgeY, 84, 42, 14, 14);
        g.setFont(new Font("SansSerif", Font.BOLD, 26));
        g.setColor(theme.badgeTextColor());
        g.drawString(rankBadge(index), badgeX + 18, badgeY + 30);

        int textX = badgeX + 96;
        int textWidth = (cardX + cardWidth) - textX - 22;
        int titleTopY = badgeY + 6;
        int titleBottomY = drawWrappedTitle(g, row.getTitle(), textX, titleTopY, textWidth, layout.maxTitleLines(), theme);
        int metricBaseline = Math.min(y + layout.entryHeight() - 24, titleBottomY + 34);

        g.setColor(theme.primaryText());
        g.setFont(new Font("SansSerif", Font.BOLD, 25));
        g.setColor(theme.accent());
        g.drawString(describeMetricValue(row), badgeX, metricBaseline);
    }

    private String describeMetricValue(TrendingManhwaDTO row) {
        return switch (row.getRankingMode()) {
            case RATE -> "Growth/day " + (row.getGrowthPerDay() == null ? "-" : ("+" + DECIMAL_FORMAT.format(row.getGrowthPerDay())));
            case ABS -> "Growth " + (row.getGrowth() == null ? "-" : ("+" + INTEGER_FORMAT.format(row.getGrowth())));
            case PCT -> "Growth " + (row.getGrowthPercent() == null ? "-" : ("+" + DECIMAL_FORMAT.format(row.getGrowthPercent() * 100) + "%"));
            case TOTAL -> "Total " + INTEGER_FORMAT.format(row.getLatestValue());
            case ENGAGEMENT -> "Engagement " + (row.getRankingScore() == null ? "-" : DECIMAL_FORMAT.format(row.getRankingScore()));
            case ACCELERATION -> "Heating Up " + (row.getRankingScore() == null ? "-" : DECIMAL_FORMAT.format(row.getRankingScore()));
            case SOCIAL -> "Social Score " + (row.getRankingScore() == null ? "-" : DECIMAL_FORMAT.format(row.getRankingScore()));
        };
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

    private BufferedImage createFallbackCover() {
        int size = 200;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = img.createGraphics();
        graphics.setPaint(new GradientPaint(0, 0, new Color(40, 40, 60), size, size, new Color(70, 70, 90)));
        graphics.fillRect(0, 0, size, size);
        graphics.setColor(new Color(255, 255, 255, 120));
        graphics.setFont(new Font("SansSerif", Font.BOLD, 40));
        FontMetrics metrics = graphics.getFontMetrics();
        String letter = "M";
        int x = (size - metrics.stringWidth(letter)) / 2;
        int y = ((size - metrics.getHeight()) / 2) + metrics.getAscent();
        graphics.drawString(letter, x, y);
        graphics.dispose();
        return img;
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

    private String buildDefaultTitle(SocialRankingImageRequest request) {
        return switch (request.getMode()) {
            case RATE -> request.getWindow() == RankingWindow.DAILY ? "Fastest Rising Today" : "Fastest Rising This Week";
            case PCT -> request.getWindow() == RankingWindow.DAILY ? "Breakout of the Day" : "Breakout of the Week";
            case ABS -> "Biggest " + (request.getWindow() == RankingWindow.DAILY ? "Daily" : "Weekly") + " Gainers";
            case ACCELERATION -> "Heating Up Fast";
            case TOTAL -> "Most Followed Right Now";
            case ENGAGEMENT -> "Fan Efficiency Leaders";
            case SOCIAL -> "Editor's Social Rank";
        };
    }

    private String buildDefaultSubtitle(SocialRankingImageRequest request) {
        String source = resolveSource(request.getSourceId());
        String metric = request.getMetric().name();
        String window = request.getWindow() == RankingWindow.DAILY ? "24h" : "7d";
        return "Top " + request.getLimit() + " | " + source + " | " + metric + " | " + window;
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

    private String rankBadge(int index) {
        return "#" + (index + 1);
    }

    private void drawFooter(
            Graphics2D g,
            SocialRankingImageRequest request,
            ThemeSpec theme,
            SafeAreaSpec safeArea,
            LayoutSpec layout
    ) {
        int footerY = IMAGE_HEIGHT - safeArea.bottomInset() - layout.footerHeight();
        int footerX = safeArea.sideInset();
        int footerWidth = IMAGE_WIDTH - (2 * safeArea.sideInset());
        int footerHeight = layout.footerHeight() - 14;

        g.setColor(withAlpha(theme.cardBackground(), 192));
        g.fill(new RoundRectangle2D.Double(footerX, footerY, footerWidth, footerHeight, 20, 20));
        g.setColor(withAlpha(theme.secondaryText(), 230));
        g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        String left = request.getCtaText() + " " + request.getCtaHandle() + " " + request.getCampaignTag();
        g.drawString(ellipsize(left, 72), footerX + 16, footerY + 28);
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

    private String sanitizeVariant(String variant, SocialRankingImageRequest request) {
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
            // Asura ranking data is follower-driven.
            if (metricType == MetricType.VIEWS || metricType == MetricType.SUBSCRIBERS || metricType == MetricType.LIKES) {
                return MetricType.FOLLOWERS;
            }
            return metricType;
        }
        if (metricType == MetricType.FOLLOWERS) {
            // Follower metric is Asura-only in current ingestion; fall back for non-Asura sources.
            return sourceId == 3 ? MetricType.SUBSCRIBERS : MetricType.VIEWS;
        }
        return metricType;
    }

    private ThemeSpec resolveTheme(String themeName, String variant) {
        ThemeSpec base = switch (themeName) {
            case "neon" -> new ThemeSpec(
                    new Color(17, 12, 40),
                    new Color(4, 8, 26),
                    new Color(70, 240, 255),
                    new Color(255, 102, 72),
                    new Color(20, 26, 48),
                    new Color(130, 236, 255),
                    new Color(255, 255, 255),
                    new Color(216, 232, 255),
                    new Color(70, 240, 255),
                    new Color(8, 18, 28)
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
                    new Color(20, 25, 52),
                    new Color(8, 12, 28),
                    new Color(88, 208, 255),
                    new Color(255, 124, 82),
                    new Color(24, 30, 56),
                    new Color(166, 232, 255),
                    new Color(255, 255, 255),
                    new Color(220, 236, 255),
                    new Color(88, 208, 255),
                    new Color(10, 20, 36)
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
            case "tiktok" -> new SafeAreaSpec(88, 190, 56);
            case "reels" -> new SafeAreaSpec(76, 150, 52);
            case "x" -> new SafeAreaSpec(52, 84, 84);
            default -> new SafeAreaSpec(64, 110, 48);
        };
    }

    private LayoutSpec resolveLayout(String format) {
        return switch (format) {
            case "tiktok" -> new LayoutSpec(184, 108, 176, 14, 2);
            case "x" -> new LayoutSpec(174, 96, 188, 18, 2);
            default -> new LayoutSpec(180, 100, 182, 14, 2);
        };
    }

    private LayoutSpec adjustLayout(LayoutSpec base, SafeAreaSpec safeArea, int rowCount) {
        if (rowCount <= 0) {
            return base;
        }
        int startY = safeArea.topInset() + base.headerHeight();
        int available = IMAGE_HEIGHT - safeArea.bottomInset() - base.footerHeight() - startY;
        int spacing = base.entrySpacing();
        if (rowCount > 1) {
            int maxSpacing = (available - (120 * rowCount)) / (rowCount - 1);
            spacing = Math.max(8, Math.min(spacing, maxSpacing));
        } else {
            spacing = 0;
        }
        int usable = available - (spacing * Math.max(0, rowCount - 1));
        int entryHeight = Math.max(120, Math.min(base.entryHeight(), usable / rowCount));
        int maxTitleLines = entryHeight >= 150 ? 2 : 1;
        return new LayoutSpec(base.headerHeight(), base.footerHeight(), entryHeight, spacing, maxTitleLines);
    }

    private int drawWrappedTitle(
            Graphics2D g,
            String rawTitle,
            int x,
            int topY,
            int maxWidth,
            int maxLines,
            ThemeSpec theme
    ) {
        String title = rawTitle == null || rawTitle.isBlank() ? "Untitled" : rawTitle.trim();
        for (int size = 33; size >= 15; size--) {
            Font font = new Font("SansSerif", Font.BOLD, size);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics(font);
            List<String> lines = wrapLines(fm, title, maxWidth);
            if (lines.size() <= maxLines) {
                g.setColor(theme.primaryText());
                int baseline = topY;
                int lineHeight = fm.getHeight() + 2;
                for (int i = 0; i < lines.size(); i++) {
                    baseline += lineHeight;
                    g.drawString(lines.get(i), x, baseline);
                }
                return baseline;
            }
        }

        Font font = new Font("SansSerif", Font.BOLD, 15);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics(font);
        List<String> lines = wrapLines(fm, title, maxWidth);
        int baseline = topY;
        int lineHeight = fm.getHeight() + 2;
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
            baseline += lineHeight;
            g.drawString(lines.get(i), x, baseline);
        }
        return baseline;
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

    private Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static class RenderStats {
        private int fallbackCovers = 0;
        private int longestTitle = 0;
    }

    private record SafeAreaSpec(int topInset, int bottomInset, int sideInset) {
    }

    private record LayoutSpec(
            int headerHeight,
            int footerHeight,
            int entryHeight,
            int entrySpacing,
            int maxTitleLines
    ) {
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
