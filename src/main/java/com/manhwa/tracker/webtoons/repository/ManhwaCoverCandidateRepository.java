package com.manhwa.tracker.webtoons.repository;

import com.manhwa.tracker.webtoons.model.ManhwaCoverCandidate;
import com.manhwa.tracker.webtoons.model.TitleSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ManhwaCoverCandidateRepository extends JpaRepository<ManhwaCoverCandidate, Long> {
    Optional<ManhwaCoverCandidate> findByManhwaIdAndSource(Long manhwaId, TitleSource source);

    List<ManhwaCoverCandidate> findAllByManhwaId(Long manhwaId);
}
