package com.manhwa.tracker.webtoons.repository;

import com.manhwa.tracker.webtoons.model.ManhwaTitle;
import com.manhwa.tracker.webtoons.model.TitleSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ManhwaTitleRepository extends JpaRepository<ManhwaTitle, Long> {
    Optional<ManhwaTitle> findFirstByNormalizedTitle(String normalizedTitle);

    List<ManhwaTitle> findByNormalizedTitle(String normalizedTitle);

    List<ManhwaTitle> findByManhwaId(Long manhwaId);

    List<ManhwaTitle> findBySource(TitleSource source);

    boolean existsByManhwaIdAndNormalizedTitleAndSourceAndLanguage(
            Long manhwaId,
            String normalizedTitle,
            TitleSource source,
            String language
    );

    boolean existsByManhwaIdAndNormalizedTitleAndSourceAndLanguageIsNull(
            Long manhwaId,
            String normalizedTitle,
            TitleSource source
    );
}
