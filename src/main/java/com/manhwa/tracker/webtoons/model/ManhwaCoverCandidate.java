package com.manhwa.tracker.webtoons.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "manhwa_cover_candidates",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_cover_candidate_manhwa_source",
                        columnNames = {"manhwaId", "source"}
                )
        },
        indexes = {
                @Index(name = "idx_cover_candidates_manhwa_id", columnList = "manhwaId")
        }
)
@Data
@NoArgsConstructor
public class ManhwaCoverCandidate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long manhwaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TitleSource source;

    @Column(nullable = false)
    private String imageUrl;

    @Column(nullable = false)
    private Integer qualityScore;

    private Integer width;
    private Integer height;

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
