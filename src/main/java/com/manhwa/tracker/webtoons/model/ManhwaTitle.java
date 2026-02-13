package com.manhwa.tracker.webtoons.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "manhwa_titles",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_manhwa_titles_identity",
                        columnNames = {"manhwaId", "normalizedTitle", "source", "language"}
                )
        },
        indexes = {
                @Index(name = "idx_manhwa_titles_manhwa_id", columnList = "manhwaId"),
                @Index(name = "idx_manhwa_titles_normalized", columnList = "normalizedTitle"),
                @Index(name = "idx_manhwa_titles_source", columnList = "source")
        }
)
@Data
@NoArgsConstructor
public class ManhwaTitle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long manhwaId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String normalizedTitle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TitleSource source = TitleSource.OTHER;

    private String language;

    private Boolean canonical = false;

    private Integer confidence;

    public ManhwaTitle(Long manhwaId, String title, String normalizedTitle, TitleSource source) {
        this.manhwaId = manhwaId;
        this.title = title;
        this.normalizedTitle = normalizedTitle;
        this.source = source == null ? TitleSource.OTHER : source;
    }
}
