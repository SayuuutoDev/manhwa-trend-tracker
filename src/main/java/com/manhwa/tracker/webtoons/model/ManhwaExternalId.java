package com.manhwa.tracker.webtoons.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "manhwa_external_ids",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_external_id_source_value",
                        columnNames = {"source", "externalId"}
                )
        },
        indexes = {
                @Index(name = "idx_external_ids_manhwa_id", columnList = "manhwaId")
        }
)
@Data
@NoArgsConstructor
public class ManhwaExternalId {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long manhwaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TitleSource source;

    @Column(nullable = false)
    private String externalId;

    private String url;

    public ManhwaExternalId(Long manhwaId, TitleSource source, String externalId, String url) {
        this.manhwaId = manhwaId;
        this.source = source;
        this.externalId = externalId;
        this.url = url;
    }
}
