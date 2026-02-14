package com.manhwa.tracker.webtoons.repository;

import com.manhwa.tracker.webtoons.model.ManhwaExternalId;
import com.manhwa.tracker.webtoons.model.TitleSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ManhwaExternalIdRepository extends JpaRepository<ManhwaExternalId, Long> {
    Optional<ManhwaExternalId> findBySourceAndExternalId(TitleSource source, String externalId);

    Optional<ManhwaExternalId> findByManhwaIdAndSource(Long manhwaId, TitleSource source);

    List<ManhwaExternalId> findAllByManhwaIdAndSource(Long manhwaId, TitleSource source);
}
