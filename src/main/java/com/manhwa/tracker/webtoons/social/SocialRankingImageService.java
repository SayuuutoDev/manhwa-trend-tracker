package com.manhwa.tracker.webtoons.social;

import com.manhwa.tracker.webtoons.model.MetricType;
import com.manhwa.tracker.webtoons.model.TitleSource;
import com.manhwa.tracker.webtoons.model.TrendingManhwaDTO;
import com.manhwa.tracker.webtoons.model.TrendingRankingMode;
import com.manhwa.tracker.webtoons.service.LocalCoverStorageService;
import com.manhwa.tracker.webtoons.service.TrendingService;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class SocialRankingImageService {
    private static final int IMAGE_WIDTH = 1080;
    private static final int IMAGE_HEIGHT = 1350;
    private static final int HEADER_HEIGHT = 220;
    private static final int FOOTER_HEIGHT = 70;
    private static final int ENTRY_HEIGHT = 178;
    private static final int ENTRY_SPACING = 14;
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
        SocialRankingImageRequest normalized = normalize(request);
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

        BufferedImage canvas = render(normalized, rows);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(canvas, "png", baos);
            return baos.toByteArray();
        }
    }

    private SocialRankingImageRequest normalize(SocialRankingImageRequest request) {
        SocialRankingImageRequest normalized = new SocialRankingImageRequest();
        normalized.setMetric(request.getMetric() == null ? MetricType.VIEWS : request.getMetric());
        normalized.setMode(request.getMode() == null ? TrendingRankingMode.RATE : request.getMode());
        normalized.setSourceId(request.getSourceId());

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
        return normalized;
    }

    private BufferedImage render(SocialRankingImageRequest request, List<TrendingManhwaDTO> rows) {
        ThemeSpec theme = resolveTheme(request.getTheme());
        BufferedImage canvas = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        applyRenderingHints(g);

        drawBackground(g, theme);
        drawHeader(g, request, theme, rows.isEmpty());
        int startY = HEADER_HEIGHT;
        if (rows.isEmpty()) {
            drawEmptyState(g, startY, IMAGE_HEIGHT - startY - FOOTER_HEIGHT, theme);
        } else {
            for (int i = 0; i < rows.size(); i++) {
                int y = startY + i * (ENTRY_HEIGHT + ENTRY_SPACING);
                drawEntry(g, rows.get(i), i, y, request.getSourceId(), theme);
            }
        }
        drawFooter(g, request, theme);

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
        g.fill(new RoundRectangle2D.Double(40, 30, IMAGE_WIDTH - 80, 180, 36, 36));
    }

    private void drawHeader(Graphics2D g, SocialRankingImageRequest request, ThemeSpec theme, boolean empty) {
        Font titleFont = new Font("SansSerif", Font.BOLD, 56);
        Font subtitleFont = new Font("SansSerif", Font.BOLD, 28);
        Font metaFont = new Font("SansSerif", Font.PLAIN, 20);

        g.setColor(theme.primaryText());
        g.setFont(titleFont);
        String title = request.getTitle() == null || request.getTitle().isBlank()
                ? "Top Manhwa This Week"
                : request.getTitle();
        g.drawString(title, 72, 108);

        String subtitle = request.getSubtitle();
        if (subtitle == null || subtitle.isBlank()) {
            subtitle = buildDefaultSubtitle(request);
        }
        g.setFont(subtitleFont);
        g.setColor(theme.secondaryText());
        g.drawString(subtitle, 74, 154);

        if (request.getIncludeTimestamp()) {
            g.setFont(metaFont);
            String timestamp = "Generated " + LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            FontMetrics metrics = g.getFontMetrics(metaFont);
            int width = metrics.stringWidth(timestamp);
            g.setColor(withAlpha(theme.primaryText(), 210));
            g.drawString(timestamp, IMAGE_WIDTH - 72 - width, 70);
        }

        if (empty) {
            g.setFont(metaFont);
            g.setColor(withAlpha(theme.primaryText(), 220));
            g.drawString("No ranking data available for this configuration.", 74, 192);
        }
    }

    private void drawEmptyState(Graphics2D g, int startY, int availableHeight, ThemeSpec theme) {
        Font messageFont = new Font("SansSerif", Font.PLAIN, 24);
        g.setFont(messageFont);
        g.setColor(withAlpha(theme.secondaryText(), 180));
        g.drawString("Nothing to rank yet. Run a scrape job and try again.", 60, startY + availableHeight / 2);
    }

    private void drawEntry(Graphics2D g, TrendingManhwaDTO row, int index, int y, Integer requestedSourceId, ThemeSpec theme) {
        int cardX = 44;
        int cardWidth = IMAGE_WIDTH - 88;
        RoundRectangle2D card = new RoundRectangle2D.Double(cardX, y, cardWidth, ENTRY_HEIGHT, 30, 30);
        g.setColor(withAlpha(theme.cardBackground(), 215));
        g.fill(card);
        g.setColor(withAlpha(theme.cardBorder(), 180));
        g.setStroke(new BasicStroke(2f));
        g.draw(card);

        int coverSize = 136;
        int coverX = cardX + 22;
        int coverY = y + (ENTRY_HEIGHT - coverSize) / 2;
        BufferedImage cover = loadCover(resolveCoverUrl(row, requestedSourceId));
        g.drawImage(cover, coverX, coverY, coverSize, coverSize, null);

        int badgeX = coverX + coverSize + 20;
        int badgeY = coverY + 6;
        g.setColor(theme.badgeColor());
        g.fillRoundRect(badgeX, badgeY, 84, 42, 14, 14);
        g.setFont(new Font("SansSerif", Font.BOLD, 26));
        g.setColor(theme.badgeTextColor());
        g.drawString(rankBadge(index), badgeX + 18, badgeY + 30);

        int textX = badgeX + 96;
        int titleY = badgeY + 32;
        g.setColor(theme.primaryText());
        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        g.drawString(ellipsize(row.getTitle(), 34), textX, titleY);

        g.setFont(new Font("SansSerif", Font.BOLD, 26));
        g.setColor(theme.accent());
        g.drawString(describeMetricValue(row), badgeX, titleY + 46);
    }

    private String describeMetricValue(TrendingManhwaDTO row) {
        return switch (row.getRankingMode()) {
            case RATE -> "Growth/day " + (row.getGrowthPerDay() == null ? "-" : ("+" + DECIMAL_FORMAT.format(row.getGrowthPerDay())));
            case ABS -> "Growth " + (row.getGrowth() == null ? "-" : ("+" + INTEGER_FORMAT.format(row.getGrowth())));
            case PCT -> "Growth " + (row.getGrowthPercent() == null ? "-" : ("+" + DECIMAL_FORMAT.format(row.getGrowthPercent() * 100) + "%"));
            case TOTAL -> "Total " + INTEGER_FORMAT.format(row.getLatestValue());
            case ENGAGEMENT -> "Engagement " + (row.getRankingScore() == null ? "-" : DECIMAL_FORMAT.format(row.getRankingScore()));
            case ACCELERATION -> "Acceleration " + (row.getRankingScore() == null ? "-" : DECIMAL_FORMAT.format(row.getRankingScore()));
        };
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

    private String buildDefaultSubtitle(SocialRankingImageRequest request) {
        String source = resolveSource(request.getSourceId());
        return "Top " + request.getLimit() + " | " + source + " | " + request.getMetric().name();
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

    private void drawFooter(Graphics2D g, SocialRankingImageRequest request, ThemeSpec theme) {
        g.setColor(withAlpha(theme.cardBackground(), 190));
        g.fill(new RoundRectangle2D.Double(44, IMAGE_HEIGHT - FOOTER_HEIGHT + 8, IMAGE_WIDTH - 88, FOOTER_HEIGHT - 20, 20, 20));
        g.setColor(withAlpha(theme.secondaryText(), 220));
        g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        g.drawString("Data: Manhwa Trend Tracker  |  theme=" + request.getTheme() + " format=" + request.getFormat(), 66, IMAGE_HEIGHT - 26);
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

    private ThemeSpec resolveTheme(String themeName) {
        return switch (themeName) {
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
    }

    private Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
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
