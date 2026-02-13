package com.manhwa.tracker.webtoons.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
@Entity
@Table(name = "manhwas")
@Data
@NoArgsConstructor
public class Manhwa {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String canonicalTitle;

    private String description;
    private String genre;         // <--- Add this line
    private String coverImageUrl;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Manhwa(String title) {
        this.canonicalTitle = title;
    }
}