package com.manhwa.tracker.webtoons.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "metric_snapshots")
@Data
public class MetricSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long manhwaId;
    private Integer sourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MetricType metricType;

    @Column(nullable = false)
    private Long metricValue;

    private LocalDateTime capturedAt = LocalDateTime.now();
}
