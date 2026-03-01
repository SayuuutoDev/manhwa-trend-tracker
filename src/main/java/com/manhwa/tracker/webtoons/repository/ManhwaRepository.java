package com.manhwa.tracker.webtoons.repository;

import com.manhwa.tracker.webtoons.model.Manhwa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ManhwaRepository extends JpaRepository<Manhwa, Long> {
    // This allows us to find a manhwa by title to get its ID
    Optional<Manhwa> findByCanonicalTitle(String title);
}