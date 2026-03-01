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
    // 4:5 portrait, optimized for social feed posts.
    private static final int IMAGE_WIDTH = 1080;
    private static final int IMAGE_HEIGHT = 1350;
    private static final int HEADER_HEIGHT = 220;
    private static final int FOOTER_HEIGHT = 70;
    private static final int ENTRY_HEIGHT = 162;
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
        int limit = request.getLimit() == null ? 6 : request.getLimit();
        normalized.setLimit(Math.max(3, Math.min(limit, 6)));
        normalized.setTitle(request.getTitle());
        normalized.setSubtitle(request.getSubtitle());
        normalized.setIncludeTimestamp(request.getIncludeTimestamp() == null ? Boolean.TRUE : request.getIncludeTimestamp());
        return normalized;
    }

    private BufferedImage render(SocialRankingImageRequest request, List<TrendingManhwaDTO> rows) {
        BufferedImage canvas = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        applyRenderingHints(g);
        drawBackground(g, IMAGE_HEIGHT);
        drawHeader(g, request, rows.isEmpty());
        int startY = HEADER_HEIGHT;
        if (rows.isEmpty()) {
            drawEmptyState(g, startY, IMAGE_HEIGHT - startY - FOOTER_HEIGHT);
        } else {
            for (int i = 0; i < rows.size(); i++) {
                int y = startY + i * (ENTRY_HEIGHT + ENTRY_SPACING);
                drawEntry(g, rows.get(i), i, y, request.getSourceId());
            }
        }
        drawFooter(g);
        g.dispose();
        return canvas;
    }

    private void drawBackground(Graphics2D g, int height) {
        GradientPaint gradient = new GradientPaint(0, 0, new Color(20, 25, 52), 0, height, new Color(8, 12, 28));
        g.setPaint(gradient);
        g.fillRect(0, 0, IMAGE_WIDTH, height);

        g.setColor(new Color(58, 183, 255, 35));
        g.fill(new Ellipse2D.Double(-180, -100, 620, 620));
        g.setColor(new Color(255, 119, 84, 30));
        g.fill(new Ellipse2D.Double(620, -140, 560, 560));
        g.setColor(new Color(255, 255, 255, 18));
        g.fill(new RoundRectangle2D.Double(40, 30, IMAGE_WIDTH - 80, 180, 36, 36));
    }

    private void drawHeader(Graphics2D g, SocialRankingImageRequest request, boolean empty) {
        Font titleFont = new Font("SansSerif", Font.BOLD, 54);
        Font subtitleFont = new Font("SansSerif", Font.BOLD, 25);
        Font metaFont = new Font("SansSerif", Font.PLAIN, 20);
        g.setColor(Color.WHITE);
        g.setFont(titleFont);
        String title = request.getTitle() == null ? "Manhwa Trend Pulse" : request.getTitle();
        g.drawString(title, 72, 105);
        g.setFont(subtitleFont);
        String subtitle = request.getSubtitle();
        if (subtitle == null || subtitle.isBlank()) {
            subtitle = buildDefaultSubtitle(request);
        }
        g.setColor(new Color(226, 236, 255));
        g.drawString(subtitle, 74, 150);
        if (request.getIncludeTimestamp()) {
            g.setFont(metaFont);
            String timestamp = "Generated " + LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            FontMetrics metrics = g.getFontMetrics(metaFont);
            int width = metrics.stringWidth(timestamp);
            g.setColor(new Color(255, 255, 255, 200));
            g.drawString(timestamp, IMAGE_WIDTH - 72 - width, 68);
        }
        if (empty) {
            g.setFont(metaFont);
            g.setColor(new Color(255, 255, 255, 210));
            g.drawString("No ranking data available for this configuration.", 74, 188);
        }
    }

    private void drawEmptyState(Graphics2D g, int startY, int availableHeight) {
        Font messageFont = new Font("SansSerif", Font.PLAIN, 24);
        g.setFont(messageFont);
        g.setColor(new Color(255, 255, 255, 120));
        String message = "Nothing to rank yet. Run a scrape job and try again.";
        g.drawString(message, 60, startY + availableHeight / 2);
    }

    private void drawEntry(Graphics2D g, TrendingManhwaDTO row, int index, int y, Integer requestedSourceId) {
        int cardX = 44;
        int cardWidth = IMAGE_WIDTH - 88;
        int cardHeight = ENTRY_HEIGHT;
        RoundRectangle2D shadow = new RoundRectangle2D.Double(cardX, y, cardWidth, cardHeight, 30, 30);
        g.setColor(new Color(255, 255, 255, 20));
        g.fill(shadow);
        g.setColor(new Color(255, 255, 255, 48));
        g.setStroke(new BasicStroke(2f));
        g.draw(shadow);
        int coverSize = 132;
        int coverX = cardX + 20;
        int coverY = y + (cardHeight - coverSize) / 2;
        BufferedImage cover = loadCover(resolveCoverUrl(row, requestedSourceId));
        g.drawImage(cover, coverX, coverY, coverSize, coverSize, null);
        int textX = coverX + coverSize + 22;
        int baseY = coverY + 30;
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 32));
        String label = rankBadge(index) + " " + ellipsize(row.getTitle(), 30);
        g.drawString(label, textX, baseY);
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.setColor(new Color(191, 230, 255));
        g.drawString(describeMetricValue(row), textX, baseY + 34);
        g.setFont(new Font("SansSerif", Font.PLAIN, 19));
        g.setColor(new Color(233, 239, 255, 215));
        g.drawString("Total: " + INTEGER_FORMAT.format(row.getLatestValue()) + " " + row.getMetricType().name().toLowerCase(Locale.ROOT), textX, baseY + 64);
        g.setColor(new Color(255, 255, 255, 165));
        g.drawString("Mode: " + row.getRankingMode().name(), textX + 360, baseY + 64);
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
        if (text == null) {
            return "Untitled";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 1) + "…";
    }

    private String buildDefaultSubtitle(SocialRankingImageRequest request) {
        String source = resolveSource(request.getSourceId());
        return "Top " + request.getLimit() + " · " + source + " · " + request.getMetric().name() + " ranked by " + request.getMode().name();
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
        return switch (index) {
            case 0 -> "#1";
            case 1 -> "#2";
            case 2 -> "#3";
            default -> "#" + (index + 1);
        };
    }

    private void drawFooter(Graphics2D g) {
        g.setColor(new Color(255, 255, 255, 65));
        g.fill(new RoundRectangle2D.Double(44, IMAGE_HEIGHT - FOOTER_HEIGHT + 8, IMAGE_WIDTH - 88, FOOTER_HEIGHT - 20, 20, 20));
        g.setColor(new Color(240, 245, 255, 210));
        g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        g.drawString("Data: Manhwa Trend Tracker  •  /api/social-ranking.png", 66, IMAGE_HEIGHT - 26);
    }
}
